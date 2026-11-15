package dev.emitkit.worker.pipeline;

import static dev.emitkit.domain.StepName.CALC_VERSION;
import static dev.emitkit.domain.StepName.DIFF_SPEC;
import static dev.emitkit.domain.StepName.FETCH_SPEC;
import static dev.emitkit.domain.StepName.LINT_SPEC;
import static dev.emitkit.domain.StepName.PARSE_SPEC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.vcs.VcsProvider;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the pipeline against PostgreSQL 18 with the domain's Flyway migrations and scripted steps.
 */
@SpringBootTest(
    classes = WRK01PipelineRunnerTest.TestApp.class,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "emitkit.vault.cipher=local"})
@Testcontainers
class WRK01PipelineRunnerTest {

  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private PlatformTransactionManager transactions;

  /** What a fake step does when it runs. */
  private interface Script {
    StepResult run(RunContext ctx) throws Exception;
  }

  private final List<StepName> order = new ArrayList<>();
  private final Map<StepName, Script> scripts = new EnumMap<>(StepName.class);
  private final FakeVcs vcs = new FakeVcs();
  private final List<String> logCalls = new ArrayList<>();
  private final RunLogger recorder =
      (RunLogger)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {RunLogger.class},
              (proxy, method, args) -> {
                logCalls.add(method.getName());
                return null;
              });
  private Instant now = EPOCH;
  private final InstantSource clock = () -> now;
  private User creator;
  private String runId;
  private CancellationRegistry cancellation;
  private Optional<String> lintOutputSeenByDiff = Optional.empty();

  @BeforeEach
  void seed() {
    // Each test gets its own user, organization and project: the schema makes those unique.
    long n = SEQUENCE.incrementAndGet();
    creator =
        users.saveAndFlush(
            new User(null, n, "user" + n, "User " + n, "user" + n + "@example.com", "https://a"));
    Organization org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, "org" + n, "Org " + n, false, null));
    Project project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                "acme/api-" + n,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    ProjectConfig config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
    runId =
        runs.saveAndFlush(
                new GenerationRun(
                    null, project.getId(), config.getId(), RunTrigger.MANUAL, false, EPOCH))
            .getId();
    cancellation = new CancellationRegistry(runs, clock);
  }

  @Test
  void runsStepsInOrder() {
    scripts.put(
        FETCH_SPEC,
        ctx -> {
          ctx.vcs();
          return new StepResult.Continue("FETCH_SPEC-out");
        });

    runner().run(runId);

    assertThat(order).containsExactly(StepName.values());
    assertThat(vcs.creators).containsExactly(creator.getId());
    assertThat(reload().getCurrentStep()).isEqualTo(StepName.FINALIZE);
    assertThat(reload().getStartedAt()).isEqualTo(EPOCH);
    assertThat(StepOutputs.read(reload().getStepOutputs(), DIFF_SPEC, String.class))
        .hasValue("DIFF_SPEC-out");
  }

  @Test
  void persistedStepIsSkippedOnResume() {
    saveOutput(FETCH_SPEC, "saved-fetch");
    saveOutput(PARSE_SPEC, "saved-parse");
    saveOutput(LINT_SPEC, "saved-lint");
    scripts.put(
        DIFF_SPEC,
        ctx -> {
          lintOutputSeenByDiff = ctx.output(LINT_SPEC, String.class);
          return new StepResult.Continue("diff");
        });

    runner().run(runId);

    assertThat(order)
        .containsExactlyElementsOf(List.of(StepName.values()).subList(3, StepName.values().length));
    assertThat(lintOutputSeenByDiff).hasValue("saved-lint");
  }

  @Test
  void failSetsFailureCode() {
    scripts.put(LINT_SPEC, ctx -> new StepResult.Fail(ErrorCode.LINT_FAILED, "2 errors"));

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("LINT_FAILED");
    assertThat(run.getFailureMessage()).isEqualTo("2 errors");
    assertThat(run.getFinishedAt()).isNotNull();
    assertThat(order).containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC);
    assertThat(logCalls).endsWith("error", "done");
  }

  @Test
  void appExceptionBecomesFail() {
    scripts.put(
        PARSE_SPEC,
        ctx -> {
          throw new AppException(ErrorCode.SPEC_INVALID, "bad spec");
        });

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("SPEC_INVALID");
    assertThat(run.getFailureMessage()).isEqualTo("bad spec");
    assertThat(order).containsExactly(FETCH_SPEC, PARSE_SPEC);
  }

  @Test
  void transientExceptionPropagates_runStaysRunning() {
    scripts.put(
        LINT_SPEC,
        ctx -> {
          throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
        });

    assertThatThrownBy(() -> runner().run(runId)).isInstanceOf(TransientException.class);

    // The current step was written before LINT ran, and no later write happened.
    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.getCurrentStep()).isEqualTo(LINT_SPEC);
    assertThat(run.getFailureCode()).isNull();

    // Redelivery: FETCH and PARSE are saved, so the run resumes at LINT.
    scripts.clear();
    order.clear();
    now = EPOCH.plusSeconds(60);
    runner().run(runId);

    assertThat(order)
        .containsExactlyElementsOf(List.of(StepName.values()).subList(2, StepName.values().length));
    assertThat(reload().getStartedAt()).isEqualTo(EPOCH);
  }

  @Test
  void parkStops() {
    scripts.put(
        LINT_SPEC,
        ctx -> {
          // A parking step persists its own status change; the runner writes nothing on Park.
          GenerationRun row = reload();
          row.setStatus(RunStatus.AWAITING_APPROVAL);
          runs.saveAndFlush(row);
          return new StepResult.Park();
        });

    runner().run(runId);

    assertThat(reload().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    assertThat(StepOutputs.has(reload().getStepOutputs(), LINT_SPEC)).isFalse();
    assertThat(order).containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC);
  }

  @Test
  void terminalRun_isNoOp() {
    GenerationRun run = reload();
    run.setStatus(RunStatus.SUCCESS);
    runs.saveAndFlush(run);

    runner().run(runId);

    assertThat(order).isEmpty();
    assertThat(vcs.creators).isEmpty();
    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(reload().getStartedAt()).isNull();
  }

  @Test
  void runFinishedAfterReadIsNotOverwritten() {
    // A duplicate delivery finishes the run after this one has read it but before it starts.
    AtomicBoolean fired = new AtomicBoolean();
    GenerationRunRepository racing =
        (GenerationRunRepository)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {GenerationRunRepository.class},
                (proxy, method, args) -> {
                  Object result = method.invoke(runs, args);
                  if (method.getName().equals("findById") && fired.compareAndSet(false, true)) {
                    GenerationRun row = reload();
                    row.setStatus(RunStatus.SUCCESS);
                    runs.saveAndFlush(row);
                  }
                  return result;
                });

    runnerWith(steps(), racing).run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(run.getStartedAt()).isNull();
    assertThat(order).isEmpty();
  }

  @Test
  void vcsFailureInsideStepFailsRunWithItsCode() {
    vcs.failure = new AppException(ErrorCode.VCS_AUTH, "re-link the project");
    scripts.put(
        FETCH_SPEC,
        ctx -> {
          ctx.vcs();
          return new StepResult.Continue("unreachable");
        });

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("VCS_AUTH");
    assertThat(run.getFailureMessage()).isEqualTo("re-link the project");
    assertThat(order).containsExactly(FETCH_SPEC);
  }

  @Test
  void vcsIsNotRequestedWhenNoStepNeedsIt() {
    vcs.failure = new AppException(ErrorCode.VCS_AUTH, "re-link the project");

    runner().run(runId);

    assertThat(reload().getCurrentStep()).isEqualTo(StepName.FINALIZE);
    assertThat(vcs.creators).isEmpty();
  }

  @Test
  void missingStepBean_failsStartup() {
    assertThatThrownBy(() -> runnerWith(steps(LINT_SPEC)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LINT_SPEC");
  }

  @Test
  void cancelRequestedInDb_isReadAtMostEveryFiveSeconds() {
    scripts.put(
        DIFF_SPEC,
        ctx -> {
          GenerationRun row = reload();
          row.setCancelRequested(true);
          runs.saveAndFlush(row);
          return new StepResult.Continue("diff");
        });
    scripts.put(
        CALC_VERSION,
        ctx -> {
          now = now.plusSeconds(5);
          return new StepResult.Continue("calc");
        });

    assertThatThrownBy(() -> runner().run(runId)).isInstanceOf(CancelledException.class);

    // The flag set during DIFF_SPEC is not read until five seconds have passed, so CALC_VERSION
    // still runs, and the next check reads the flag and stops the run.
    assertThat(order).containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC, DIFF_SPEC, CALC_VERSION);
    assertThat(reload().getCurrentStep()).isEqualTo(CALC_VERSION);
  }

  /** One fake step per name, except the omitted ones. Each continues with a fixed output. */
  private List<PipelineStep> steps(StepName... omit) {
    List<StepName> omitted = List.of(omit);
    List<PipelineStep> result = new ArrayList<>();
    for (StepName name : StepName.values()) {
      if (!omitted.contains(name)) {
        result.add(fakeStep(name));
      }
    }
    return result;
  }

  private PipelineStep fakeStep(StepName name) {
    return new PipelineStep() {
      @Override
      public StepName name() {
        return name;
      }

      @Override
      public StepResult execute(RunContext ctx) throws Exception {
        order.add(name);
        Script script = scripts.get(name);
        return script == null ? new StepResult.Continue(name.name() + "-out") : script.run(ctx);
      }
    };
  }

  private PipelineRunner runner() {
    return runnerWith(steps());
  }

  private PipelineRunner runnerWith(List<PipelineStep> pipelineSteps) {
    return runnerWith(pipelineSteps, runs);
  }

  private PipelineRunner runnerWith(
      List<PipelineStep> pipelineSteps, GenerationRunRepository repo) {
    return new PipelineRunner(
        pipelineSteps,
        repo,
        projects,
        configs,
        vcs.provider(),
        transactions,
        cancellation,
        id -> recorder,
        clock);
  }

  private GenerationRun reload() {
    return runs.findById(runId).orElseThrow();
  }

  private void saveOutput(StepName step, String output) {
    GenerationRun run = reload();
    run.setStepOutputs(StepOutputs.with(run.getStepOutputs(), step, output));
    runs.saveAndFlush(run);
  }

  /** Records the project creators the runner asks a client for. Throws {@code failure} if set. */
  static final class FakeVcs {
    final List<String> creators = new ArrayList<>();
    AppException failure;

    VcsProvider provider() {
      return (VcsProvider)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {VcsProvider.class},
              (proxy, method, args) -> {
                if (!method.getName().equals("forProject") || args.length != 1) {
                  throw new UnsupportedOperationException(method.getName());
                }
                creators.add((String) args[0]);
                if (failure != null) {
                  throw failure;
                }
                return null;
              });
    }
  }

  /** Boots JPA and Flyway against the container, with the domain's entities and repositories. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
