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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Runs the pipeline against PostgreSQL 18 with the domain's Flyway migrations. The steps are
 * scripted fakes that record the order they run in.
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

  private final Steps steps = new Steps();
  private final FakeVcs vcs = new FakeVcs();
  private final TestClock clock = new TestClock(EPOCH);
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
    runner().run(runId);

    assertThat(steps.order).containsExactly(StepName.values());
    assertThat(vcs.creators).containsExactly(creator.getId());
    assertThat(reload().getCurrentStep()).isEqualTo(StepName.FINALIZE);
    assertThat(StepOutputs.read(reload().getStepOutputs(), DIFF_SPEC, String.class))
        .hasValue("DIFF_SPEC-out");
  }

  @Test
  void persistedStepIsSkippedOnResume() {
    saveOutput(FETCH_SPEC, "saved-fetch");
    saveOutput(PARSE_SPEC, "saved-parse");
    saveOutput(LINT_SPEC, "saved-lint");
    steps.on(
        DIFF_SPEC,
        ctx -> {
          lintOutputSeenByDiff = ctx.output(LINT_SPEC, String.class);
          return new StepResult.Continue("diff");
        });

    runner().run(runId);

    assertThat(steps.order)
        .containsExactlyElementsOf(List.of(StepName.values()).subList(3, StepName.values().length));
    assertThat(lintOutputSeenByDiff).hasValue("saved-lint");
  }

  @Test
  void failSetsFailureCode() {
    steps.on(LINT_SPEC, ctx -> new StepResult.Fail(ErrorCode.LINT_FAILED, "2 errors"));

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("LINT_FAILED");
    assertThat(run.getFailureMessage()).isEqualTo("2 errors");
    assertThat(run.getFinishedAt()).isNotNull();
    assertThat(steps.order).containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC);
  }

  @Test
  void appExceptionBecomesFail() {
    steps.on(
        PARSE_SPEC,
        ctx -> {
          throw new AppException(ErrorCode.SPEC_INVALID, "bad spec");
        });

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("SPEC_INVALID");
    assertThat(run.getFailureMessage()).isEqualTo("bad spec");
    assertThat(steps.order).containsExactly(FETCH_SPEC, PARSE_SPEC);
  }

  @Test
  void transientExceptionPropagates_runStaysRunning() {
    steps.on(
        LINT_SPEC,
        ctx -> {
          throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
        });

    assertThatThrownBy(() -> runner().run(runId)).isInstanceOf(TransientException.class);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.getFailureCode()).isNull();
  }

  @Test
  void parkStops() {
    steps.on(
        LINT_SPEC,
        ctx -> {
          // A parking step persists its own status change; the runner writes nothing on Park.
          GenerationRun run = ctx.run();
          run.setStatus(RunStatus.AWAITING_APPROVAL);
          runs.saveAndFlush(run);
          return new StepResult.Park();
        });

    runner().run(runId);

    assertThat(reload().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    assertThat(steps.order).containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC);
  }

  @Test
  void terminalRun_isNoOp() {
    GenerationRun run = reload();
    run.setStatus(RunStatus.SUCCESS);
    runs.saveAndFlush(run);

    runner().run(runId);

    assertThat(steps.order).isEmpty();
    assertThat(vcs.creators).isEmpty();
    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(reload().getStartedAt()).isNull();
  }

  @Test
  void runFinishedAfterReadIsNotOverwritten() {
    // A duplicate delivery finishes the run after this one has read it but before it starts.
    GenerationRunRepository racing =
        racingAfterFirstRead(() -> {
          GenerationRun row = reload();
          row.setStatus(RunStatus.SUCCESS);
          runs.saveAndFlush(row);
        });

    runnerWith(steps.all(), racing).run(runId);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(run.getStartedAt()).isNull();
    assertThat(steps.order).isEmpty();
  }

  @Test
  void missingStepBean_failsStartup() {
    assertThatThrownBy(() -> runnerWith(steps.all(LINT_SPEC)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LINT_SPEC");

    List<PipelineStep> withDuplicate = new ArrayList<>(steps.all());
    withDuplicate.add(new FakeStep(FETCH_SPEC, steps));
    assertThatThrownBy(() -> runnerWith(withDuplicate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FETCH_SPEC");
  }

  @Test
  void cancelRequestedInDb_isReadAtMostEveryFiveSeconds() {
    steps.on(
        DIFF_SPEC,
        ctx -> {
          GenerationRun run = ctx.run();
          run.setCancelRequested(true);
          runs.saveAndFlush(run);
          return new StepResult.Continue("diff");
        });
    steps.on(
        CALC_VERSION,
        ctx -> {
          clock.advance(Duration.ofSeconds(5));
          return new StepResult.Continue("calc");
        });

    assertThatThrownBy(() -> runner().run(runId)).isInstanceOf(CancelledException.class);

    // The flag set during DIFF_SPEC is not read until five seconds have passed, so CALC_VERSION
    // still runs, and the next check reads the flag and stops the run.
    assertThat(steps.order)
        .containsExactly(FETCH_SPEC, PARSE_SPEC, LINT_SPEC, DIFF_SPEC, CALC_VERSION);
    assertThat(reload().getCurrentStep()).isEqualTo(CALC_VERSION);
  }

  /** A repository that runs {@code afterFirstRead} once, right after its first findById. */
  private GenerationRunRepository racingAfterFirstRead(Runnable afterFirstRead) {
    AtomicBoolean fired = new AtomicBoolean();
    return (GenerationRunRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {GenerationRunRepository.class},
            (proxy, method, args) -> {
              Object result = method.invoke(runs, args);
              if (method.getName().equals("findById") && fired.compareAndSet(false, true)) {
                afterFirstRead.run();
              }
              return result;
            });
  }

  private PipelineRunner runner() {
    return runnerWith(steps.all());
  }

  private PipelineRunner runnerWith(List<PipelineStep> pipelineSteps) {
    return runnerWith(pipelineSteps, runs);
  }

  private PipelineRunner runnerWith(
      List<PipelineStep> pipelineSteps, GenerationRunRepository repo) {
    return new PipelineRunner(
        pipelineSteps, repo, projects, configs, vcs.provider(), transactions, cancellation, clock);
  }

  private GenerationRun reload() {
    return runs.findById(runId).orElseThrow();
  }

  private void saveOutput(StepName step, String output) {
    GenerationRun run = reload();
    run.setStepOutputs(StepOutputs.with(run.getStepOutputs(), step, output));
    runs.saveAndFlush(run);
  }

  /** Scripted step bodies, plus the order in which the steps ran. */
  static final class Steps {
    final List<StepName> order = new ArrayList<>();
    final Map<StepName, Script> scripts = new EnumMap<>(StepName.class);

    /** What a fake step does when it runs. */
    interface Script {
      StepResult run(RunContext ctx) throws Exception;
    }

    void on(StepName name, Script script) {
      scripts.put(name, script);
    }

    /** One fake step per name, except the omitted ones. Each continues with a fixed output. */
    List<PipelineStep> all(StepName... omit) {
      List<StepName> omitted = List.of(omit);
      List<PipelineStep> result = new ArrayList<>();
      for (StepName name : StepName.values()) {
        if (!omitted.contains(name)) {
          result.add(new FakeStep(name, this));
        }
      }
      return result;
    }
  }

  private record FakeStep(StepName name, Steps steps) implements PipelineStep {
    @Override
    public StepResult execute(RunContext ctx) throws Exception {
      steps.order.add(name);
      Steps.Script script = steps.scripts.get(name);
      return script == null ? new StepResult.Continue(name.name() + "-out") : script.run(ctx);
    }
  }

  /** Records the project creators the runner asks a client for. Hands out no real client. */
  static final class FakeVcs {
    final List<String> creators = new ArrayList<>();

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
                return null;
              });
    }
  }

  /** A clock the test moves by hand, so the five-second recheck needs no sleeping. */
  static final class TestClock extends Clock {
    private Instant now;

    TestClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Boots JPA and Flyway against the container, with the domain's entities and repositories. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
