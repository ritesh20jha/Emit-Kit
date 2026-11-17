package dev.emitkit.worker.pipeline;

import static dev.emitkit.domain.StepName.CALC_VERSION;
import static dev.emitkit.domain.StepName.COMMIT_OUTPUT;
import static dev.emitkit.domain.StepName.DIFF_SPEC;
import static dev.emitkit.domain.StepName.FETCH_SPEC;
import static dev.emitkit.domain.StepName.FINALIZE;
import static dev.emitkit.domain.StepName.LINT_SPEC;
import static dev.emitkit.domain.StepName.PACKAGE_ARTIFACT;
import static dev.emitkit.domain.StepName.PARSE_SPEC;
import static dev.emitkit.domain.StepName.RUN_GENERATORS;
import static dev.emitkit.domain.StepName.SYNTAX_CHECK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.ai.AiProperties;
import dev.emitkit.ai.ChatModelFactory;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.spec.model.Info;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.worker.messaging.WorkerProperties;
import dev.emitkit.worker.steps.GeneratorRegistry;
import dev.emitkit.worker.steps.RunGeneratorsStep;
import dev.emitkit.worker.steps.WorkDir;
import dev.emitkit.worker.steps.WorkDirSweeper;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private SecretCipher cipher;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path workRoot;

  /** What a fake step does when it runs. */
  private interface Script {
    StepResult run(RunContext ctx) throws Exception;
  }

  private final List<StepName> order = new ArrayList<>();
  private final Map<StepName, Script> scripts = new EnumMap<>(StepName.class);
  private final FakeVcs vcs = new FakeVcs();
  private final List<String> logCalls = new ArrayList<>();
  private final List<String> masked = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final RunLogger recorder =
      (RunLogger)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {RunLogger.class},
              (proxy, method, args) -> {
                logCalls.add(method.getName());
                if (method.getName().equals("mask")) {
                  masked.add((String) args[0]);
                }
                if (method.getName().equals("warn")) {
                  warnings.add((String) args[0]);
                }
                return null;
              });
  private Instant now = EPOCH;
  private final InstantSource clock = () -> now;
  private User creator;
  private String runId;
  private long n;
  private CancellationRegistry cancellation;
  private Optional<String> lintOutputSeenByDiff = Optional.empty();

  @BeforeEach
  void seed() {
    // Each test gets its own user, organization and project: the schema makes those unique.
    n = SEQUENCE.incrementAndGet();
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
                "acme/PetStore-" + n,
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

  @Test
  void missingWorkDir_reRunsGeneratorsAndLaterStepsInRange() {
    savePriorSteps("X");

    runner().run(runId);

    assertThat(order)
        .containsExactly(RUN_GENERATORS, SYNTAX_CHECK, PACKAGE_ARTIFACT, COMMIT_OUTPUT, FINALIZE);
  }

  @Test
  void generationMismatch_reRunsGeneratorsAndClearsStaleOutputs() {
    // The work dir holds generation Y, but the saved output names X: the files are not these.
    new WorkDir(workRoot).write(runId, "Y", List.of());
    savePriorSteps("X");
    AtomicBoolean failed = new AtomicBoolean();
    scripts.put(
        RUN_GENERATORS,
        ctx -> {
          new WorkDir(workRoot).write(runId, "Z", List.of());
          return new StepResult.Continue(new RunGeneratorsStep.Output(0, "Z"));
        });
    scripts.put(
        SYNTAX_CHECK,
        ctx -> {
          if (failed.compareAndSet(false, true)) {
            throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
          }
          return new StepResult.Continue("SYNTAX_CHECK-out");
        });

    // SYNTAX_CHECK fails once after the regenerate; the stale outputs of the later steps are
    // cleared.
    assertThatThrownBy(() -> runner().run(runId)).isInstanceOf(TransientException.class);
    assertThat(order).containsExactly(RUN_GENERATORS, SYNTAX_CHECK);
    assertThat(StepOutputs.has(reload().getStepOutputs(), SYNTAX_CHECK)).isFalse();
    assertThat(StepOutputs.has(reload().getStepOutputs(), PACKAGE_ARTIFACT)).isFalse();

    // Redelivery: SYNTAX_CHECK runs rather than skipping a stale output.
    order.clear();
    runner().run(runId);
    assertThat(order).containsExactly(SYNTAX_CHECK, PACKAGE_ARTIFACT, COMMIT_OUTPUT, FINALIZE);
  }

  @Test
  void generationMatches_skipsGeneratorsAndLaterStepsInRange() {
    new WorkDir(workRoot).write(runId, "X", List.of());
    savePriorSteps("X");

    runner().run(runId);

    assertThat(order).containsExactly(COMMIT_OUTPUT, FINALIZE);
  }

  @Test
  void missingWorkDir_afterCommit_skipsEverything() {
    // A PR already exists, so the output steps are never re-run, even without the work dir.
    savePriorSteps("X");
    saveOutput(COMMIT_OUTPUT, "saved");

    runner().run(runId);

    assertThat(order).containsExactly(FINALIZE);
  }

  /** Saves every output up to PACKAGE_ARTIFACT, with RUN_GENERATORS naming {@code generation}. */
  private void savePriorSteps(String generation) {
    for (StepName name : StepName.values()) {
      if (name.compareTo(PACKAGE_ARTIFACT) <= 0) {
        saveOutput(
            name, name == RUN_GENERATORS ? new RunGeneratorsStep.Output(2, generation) : "saved");
      }
    }
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
        clock,
        new WorkDir(workRoot));
  }

  private GenerationRun reload() {
    return runs.findById(runId).orElseThrow();
  }

  private void saveOutput(StepName step, Object output) {
    GenerationRun run = reload();
    run.setStepOutputs(StepOutputs.with(run.getStepOutputs(), step, output));
    runs.saveAndFlush(run);
  }

  /** RUN_GENERATORS through the real runner: selection, AI fallback, failures and cancellation. */
  @Nested
  class WRK06RunGenerators {

    private static final String SDK_VERSION = "1.2.0";
    private static final String AI_KEY = "sk-test-key-123";

    private final Map<GeneratorId, Function<GeneratorContext, List<GeneratedFile>>> behaviour =
        new EnumMap<>(GeneratorId.class);
    private final List<GeneratorId> invoked = Collections.synchronizedList(new ArrayList<>());
    private final List<GeneratorContext> contexts = Collections.synchronizedList(new ArrayList<>());

    /** The default Gemini model; null means none is configured. */
    private String defaultModel = "gemini-test";

    @Test
    void onlySelectedGeneratorsRun() {
      configure(List.of(OutputKind.CLI, OutputKind.DOCS), List.of(), null);

      runner().run(runId);

      assertThat(invoked).containsExactlyInAnyOrder(GeneratorId.CLI, GeneratorId.DOCS);
      assertThat(reload().getCurrentStep()).isEqualTo(FINALIZE);
      assertThat(workRoot.resolve(runId).resolve("files/cli/out.txt")).exists();
      assertThat(workRoot.resolve(runId).resolve("files/docs/out.txt")).exists();
      // The column is jsonb, which Postgres re-serializes with a space after each colon.
      assertThat(reload().getStepOutputs()).contains("\"fileCount\": 2");
    }

    @Test
    void workflowsAddedWithSdk() {
      configure(List.of(OutputKind.SDK), List.of("typescript", "python"), null);

      runner().run(runId);

      assertThat(invoked)
          .containsExactlyInAnyOrder(GeneratorId.TS_SDK, GeneratorId.PY_SDK, GeneratorId.WORKFLOWS);
      GeneratorContext ctx = contexts.getFirst();
      assertThat(ctx.version()).isEqualTo(SDK_VERSION);
      assertThat(ctx.outputDir()).isEqualTo(".emitkit/");
      assertThat(ctx.sdkLanguages()).containsExactly("typescript", "python");
      assertThat(ctx.names().npmName()).isEqualTo("petstore-" + n);
    }

    @Test
    void unknownSdkLanguage_failsRunBeforeAnyGenerator() {
      configure(List.of(OutputKind.SDK), List.of("rust"), null);

      runner().run(runId);

      GenerationRun run = reload();
      assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
      assertThat(run.getFailureCode()).isEqualTo("GENERATOR_ERROR");
      assertThat(run.getFailureMessage()).contains("rust");
      assertThat(invoked).isEmpty();
    }

    @Test
    void generatorThrows_failsNamingIt() {
      behaviour.put(
          GeneratorId.PY_SDK,
          ctx -> {
            throw new IllegalStateException("template missing");
          });
      configure(List.of(OutputKind.SDK), List.of("typescript", "python"), null);

      runner().run(runId);

      GenerationRun run = reload();
      assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
      assertThat(run.getFailureCode()).isEqualTo("GENERATOR_ERROR");
      assertThat(run.getFailureMessage()).contains("PY_SDK").contains("template missing");
    }

    @Test
    void noAiKey_contextHasNoAi() {
      configure(List.of(OutputKind.DOCS), List.of(), null);

      runner().run(runId);

      assertThat(contexts.getFirst().ai()).isEmpty();
      assertThat(masked).isEmpty();
    }

    @Test
    void aiKeyMaskedInLogs() {
      configure(List.of(OutputKind.DOCS), List.of(), AI_KEY);

      runner().run(runId);

      assertThat(masked).containsExactly(AI_KEY);
      assertThat(contexts.getFirst().ai()).isPresent();
    }

    @Test
    void aiSetupFails_docsStillGenerateWithoutAi() {
      // No model on the config and none configured by default: the docs assistant cannot be built.
      defaultModel = null;
      configure(List.of(OutputKind.DOCS), List.of(), AI_KEY);

      runner().run(runId);

      assertThat(invoked).containsExactly(GeneratorId.DOCS);
      assertThat(contexts.getFirst().ai()).isEmpty();
      assertThat(warnings)
          .containsExactly("AI assistant unavailable, docs use templates: IllegalStateException")
          .allSatisfy(warning -> assertThat(warning).doesNotContain(AI_KEY));
    }

    @Test
    void unsafeGeneratorPath_failsRunWithoutWritingOutsideFiles() {
      // GeneratedFile rejects the path inside the generator, so the run fails naming the generator.
      behaviour.put(
          GeneratorId.CLI,
          ctx -> List.of(new GeneratedFile("../escaped.txt", "x", FileKind.GENERATED)));
      configure(List.of(OutputKind.CLI), List.of(), null);

      runner().run(runId);

      GenerationRun run = reload();
      assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
      assertThat(run.getFailureCode()).isEqualTo("GENERATOR_ERROR");
      assertThat(run.getFailureMessage()).contains("CLI");
      assertThat(workRoot.resolve(runId).resolve("escaped.txt")).doesNotExist();
      assertThat(workRoot.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void cancelWhileGenerating_stops() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch stopped = new CountDownLatch(1);
      behaviour.put(
          GeneratorId.CLI,
          ctx -> {
            started.countDown();
            try {
              Thread.sleep(Duration.ofMinutes(10));
            } catch (InterruptedException e) {
              stopped.countDown();
              Thread.currentThread().interrupt();
            }
            return List.of();
          });
      configure(List.of(OutputKind.CLI), List.of(), null);
      CompletableFuture<Void> running = CompletableFuture.runAsync(() -> runner().run(runId));
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

      cancellation.add(runId);

      assertThatThrownBy(() -> running.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancelledException.class);
      assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
      GenerationRun run = reload();
      assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
      assertThat(run.getFailureCode()).isNull();
    }

    private void configure(List<OutputKind> outputs, List<String> languages, String aiKey) {
      ProjectConfig config = configs.findById(reload().getConfigId()).orElseThrow();
      config.setOutputs(new ArrayList<>(outputs));
      config.setSdkLanguages(new ArrayList<>(languages));
      if (aiKey != null) {
        config.setAiApiKeyEnc(
            cipher.encrypt(
                aiKey, new SecretContext("project_configs", "ai_api_key_enc", config.getId())));
      }
      configs.saveAndFlush(config);
      ApiModel model =
          new ApiModel(
              new Info("Petstore", "1.0.0", null, null),
              List.of(),
              Map.of(),
              List.of(),
              List.of(),
              Map.of());
      SpecSnapshot snapshot =
          snapshots.saveAndFlush(
              new SpecSnapshot(
                  null,
                  reload().getProjectId(),
                  "hash-" + n,
                  "raw",
                  ApiModelJson.toJson(model),
                  "1.0.0"));
      jdbc.update(
          "update generation_runs set spec_snapshot_id = ?, sdk_version = ? where id = ?",
          snapshot.getId(),
          SDK_VERSION,
          runId);
    }

    private PipelineRunner runner() {
      List<PipelineStep> pipeline = new ArrayList<>(steps(RUN_GENERATORS));
      pipeline.add(
          new RunGeneratorsStep(
              new GeneratorRegistry(generators()),
              snapshots,
              new WorkDir(workRoot),
              cipher,
              new ChatModelFactory(new AiProperties(defaultModel, Duration.ofSeconds(30), 1))));
      return runnerWith(pipeline);
    }

    /** A generator per id: one file each, unless a test replaces its behaviour. */
    private List<Generator> generators() {
      List<Generator> all = new ArrayList<>();
      for (GeneratorId id : GeneratorId.values()) {
        all.add(
            new Generator() {
              public GeneratorId id() {
                return id;
              }

              public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
                invoked.add(id);
                contexts.add(ctx);
                Function<GeneratorContext, List<GeneratedFile>> custom = behaviour.get(id);
                return custom != null
                    ? custom.apply(ctx)
                    : List.of(
                        new GeneratedFile(
                            id.name().toLowerCase(Locale.ROOT) + "/out.txt",
                            "generated",
                            FileKind.GENERATED));
              }
            });
      }
      return all;
    }
  }

  /** The sweeper against real rows: a directory goes only when its run is finished or unknown. */
  @Nested
  class WorkDirSweep {

    @ParameterizedTest(name = "{0} run, {1}h old, deleted={2}")
    @CsvSource({
      "SUCCESS,2,true",
      "SUCCESS,0,false",
      "RUNNING,2,false",
      "UNKNOWN,2,true",
      "NOT_RUN_ID,2,false"
    })
    void sweepsOnlyOldDirsOfFinishedOrUnknownRuns(String status, long hoursOld, boolean deleted)
        throws IOException {
      String name = runId;
      if (status.equals("NOT_RUN_ID")) {
        name = "not-a-run";
      } else if (status.equals("UNKNOWN")) {
        name = Ids.newUlid();
      } else {
        GenerationRun run = reload();
        run.setStatus(RunStatus.valueOf(status));
        runs.saveAndFlush(run);
      }
      new WorkDir(workRoot).write(name, "g", List.of());
      Files.setLastModifiedTime(
          workRoot.resolve(name), FileTime.from(Instant.now().minus(Duration.ofHours(hoursOld))));

      new WorkDirSweeper(
              new WorkDir(workRoot),
              runs,
              new WorkerProperties(
                  List.of(), workRoot.toString(), Duration.ofHours(1), Duration.ofMinutes(10)))
          .sweep();

      assertThat(workRoot.resolve(name).toFile().exists()).isEqualTo(!deleted);
    }
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
