package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.FINALIZE;
import static dev.emitkit.domain.StepName.RUN_GENERATORS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.ai.AiProperties;
import dev.emitkit.ai.ChatModelFactory;
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
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineRunner;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs RUN_GENERATORS through the real pipeline runner, against PostgreSQL 18. */
@SpringBootTest(
    classes = WRK06RunGeneratorsTest.TestApp.class,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "emitkit.vault.cipher=local"})
@Testcontainers
class WRK06RunGeneratorsTest {

  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");
  private static final String SDK_VERSION = "1.2.0";
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private SecretCipher cipher;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path workRoot;

  /** Generators that replace the default one-file output, by id. */
  private final Map<GeneratorId, Function<GeneratorContext, List<GeneratedFile>>> behaviour =
      new EnumMap<>(GeneratorId.class);

  private final List<GeneratorId> invoked = Collections.synchronizedList(new ArrayList<>());
  private final List<GeneratorContext> contexts = Collections.synchronizedList(new ArrayList<>());
  private final List<String> masked = Collections.synchronizedList(new ArrayList<>());
  private final RunLogger logger =
      (RunLogger)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {RunLogger.class},
              (proxy, method, args) -> {
                if (method.getName().equals("mask")) {
                  masked.add((String) args[0]);
                }
                return null;
              });
  private CancellationRegistry cancellation;
  private long n;
  private String repoName;

  @BeforeEach
  void seed() {
    n = SEQUENCE.incrementAndGet();
    repoName = "petstore-" + n;
    cancellation = new CancellationRegistry(runs);
  }

  @Test
  void onlySelectedGeneratorsRun() {
    String runId = seedRun(List.of(OutputKind.CLI, OutputKind.DOCS), List.of(), null);

    runner().run(runId);

    assertThat(invoked).containsExactlyInAnyOrder(GeneratorId.CLI, GeneratorId.DOCS);
    assertThat(reload(runId).getCurrentStep()).isEqualTo(FINALIZE);
    assertThat(workRoot.resolve(runId).resolve("files/cli/out.txt")).exists();
    assertThat(workRoot.resolve(runId).resolve("files/docs/out.txt")).exists();
    // The column is jsonb, which Postgres re-serializes with a space after each colon.
    assertThat(reload(runId).getStepOutputs()).contains("\"RUN_GENERATORS\": {\"fileCount\": 2}");
  }

  @Test
  void workflowsAddedWithSdk() {
    String runId = seedRun(List.of(OutputKind.SDK), List.of("typescript", "python"), null);

    runner().run(runId);

    assertThat(invoked)
        .containsExactlyInAnyOrder(GeneratorId.TS_SDK, GeneratorId.PY_SDK, GeneratorId.WORKFLOWS);
    GeneratorContext ctx = contexts.getFirst();
    assertThat(ctx.version()).isEqualTo(SDK_VERSION);
    assertThat(ctx.outputDir()).isEqualTo(".emitkit/");
    assertThat(ctx.sdkLanguages()).containsExactly("typescript", "python");
    assertThat(ctx.names().npmName()).isEqualTo(repoName);
  }

  @Test
  void unknownSdkLanguage_failsRunBeforeAnyGenerator() {
    String runId = seedRun(List.of(OutputKind.SDK), List.of("rust"), null);

    runner().run(runId);

    GenerationRun run = reload(runId);
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
    String runId = seedRun(List.of(OutputKind.SDK), List.of("typescript", "python"), null);

    runner().run(runId);

    GenerationRun run = reload(runId);
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("GENERATOR_ERROR");
    assertThat(run.getFailureMessage()).contains("PY_SDK").contains("template missing");
  }

  @Test
  void noAiKey_contextHasNoAi() {
    String runId = seedRun(List.of(OutputKind.DOCS), List.of(), null);

    runner().run(runId);

    assertThat(contexts.getFirst().ai()).isEmpty();
    assertThat(masked).isEmpty();
  }

  @Test
  void aiKeyMaskedInLogs() {
    String runId = seedRun(List.of(OutputKind.DOCS), List.of(), "sk-test-key-123");

    runner().run(runId);

    assertThat(masked).containsExactly("sk-test-key-123");
    assertThat(contexts.getFirst().ai()).isPresent();
    assertThat(reload(runId).getCurrentStep()).isEqualTo(FINALIZE);
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
    String runId = seedRun(List.of(OutputKind.CLI), List.of(), null);
    CompletableFuture<Void> running = CompletableFuture.runAsync(() -> runner().run(runId));
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

    cancellation.add(runId);

    assertThatThrownBy(() -> running.get(10, TimeUnit.SECONDS))
        .hasCauseInstanceOf(CancelledException.class);
    assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
    GenerationRun run = reload(runId);
    assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.getFailureCode()).isNull();
  }

  private PipelineRunner runner() {
    // Every step before and after RUN_GENERATORS is a stub that continues.
    List<PipelineStep> steps = new ArrayList<>();
    for (StepName name : StepName.values()) {
      steps.add(
          name == RUN_GENERATORS
              ? new RunGeneratorsStep(
                  registry(),
                  snapshots,
                  new WorkDir(workRoot),
                  cipher,
                  new ChatModelFactory(new AiProperties("gemini-test", Duration.ofSeconds(30), 1)),
                  Duration.ofMillis(10))
              : stub(name));
    }
    return new PipelineRunner(
        steps,
        runs,
        projects,
        configs,
        vcsUnused(),
        transactions,
        cancellation,
        id -> logger,
        InstantSource.system(),
        new WorkDir(workRoot));
  }

  private static PipelineStep stub(StepName name) {
    return new PipelineStep() {
      @Override
      public StepName name() {
        return name;
      }

      @Override
      public StepResult execute(RunContext ctx) {
        return new StepResult.Continue(name.name());
      }
    };
  }

  private GeneratorRegistry registry() {
    List<Generator> all = new ArrayList<>();
    for (GeneratorId id : GeneratorId.values()) {
      all.add(
          new Generator() {
            @Override
            public GeneratorId id() {
              return id;
            }

            @Override
            public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
              invoked.add(id);
              contexts.add(ctx);
              Function<GeneratorContext, List<GeneratedFile>> custom = behaviour.get(id);
              return custom != null ? custom.apply(ctx) : List.of(defaultFile(id));
            }
          });
    }
    return new GeneratorRegistry(all);
  }

  private static GeneratedFile defaultFile(GeneratorId id) {
    return new GeneratedFile(
        id.name().toLowerCase(Locale.ROOT) + "/out.txt", "generated", FileKind.GENERATED);
  }

  /** Seeds project, config, snapshot and run. The key, if given, is stored encrypted. */
  private String seedRun(List<OutputKind> outputs, List<String> languages, @Nullable String aiKey) {
    User creator =
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
                "acme/" + repoName,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    ProjectConfig config =
        new ProjectConfig(
            null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId());
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
                null, project.getId(), "hash-" + n, "raw", ApiModelJson.toJson(model), "1.0.0"));
    String runId =
        runs.saveAndFlush(
                new GenerationRun(
                    null, project.getId(), config.getId(), RunTrigger.MANUAL, false, EPOCH))
            .getId();
    // The run's spec snapshot and version are set by earlier steps; seed them directly.
    jdbc.update(
        "update generation_runs set spec_snapshot_id = ?, sdk_version = ? where id = ?",
        snapshot.getId(),
        SDK_VERSION,
        runId);
    return runId;
  }

  private GenerationRun reload(String runId) {
    return runs.findById(runId).orElseThrow();
  }

  /** The runner only calls the VCS provider when a step asks for it; none does here. */
  private VcsProvider vcsUnused() {
    return (VcsProvider)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {VcsProvider.class},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** Boots JPA, Flyway and the cipher against the container, with the domain's entities. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
