package dev.emitkit.worker.messaging;

import static dev.emitkit.domain.StepName.COMMIT_OUTPUT;
import static dev.emitkit.domain.StepName.FETCH_SPEC;
import static dev.emitkit.domain.StepName.LINT_SPEC;
import static dev.emitkit.domain.StepName.SYNTAX_CHECK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.messaging.ControlMessage;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.ChangeType;
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
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import dev.emitkit.worker.logs.RunLoggerFactory;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineRunner;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import dev.emitkit.worker.steps.CalcVersionStep;
import dev.emitkit.worker.steps.FinalizeStep;
import dev.emitkit.worker.steps.RunGeneratorsStep;
import dev.emitkit.worker.steps.WorkDir;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The job listener against RabbitMQ, PostgreSQL and Redis: retries, the dead-letter queue,
 * cancellation and duplicate deliveries. Every step is a fake except FINALIZE, which is the real
 * step. Retry delays are 200 ms so the tests run quickly.
 */
@SpringBootTest(
    classes = WRK11RetryCancelIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "emitkit.vault.cipher=local",
      "emitkit.worker.pipeline.enabled=true",
      "emitkit.worker.retry-ttls=200ms,200ms,200ms"
    })
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class WRK11RetryCancelIT {

  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final Duration POLL = Duration.ofMillis(50);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private SdkVersionRepository sdkVersions;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private Fakes fakes;

  private String projectId;
  private String runId;
  private String repo;
  private String branch;

  @BeforeEach
  void seed() {
    long n = SEQUENCE.incrementAndGet();
    fakes.reset();
    // Each test gets its own user, organization, project and run: the schema makes those unique.
    User creator =
        users.saveAndFlush(
            new User(null, n, "user" + n, "User " + n, "user" + n + "@example.com", "https://a"));
    Organization org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, "org" + n, "Org " + n, false, null));
    repo = "acme/PetStore-" + n;
    Project project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                repo,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    ProjectConfig config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
    GenerationRun run =
        runs.saveAndFlush(
            new GenerationRun(
                null, project.getId(), config.getId(), RunTrigger.MANUAL, false, EPOCH));
    run.setChangeType(ChangeType.MINOR);
    runs.saveAndFlush(run);
    projectId = project.getId();
    runId = run.getId();
    branch = "emitkit/run-" + runId;
    fakes.vcs().createRepo("acme", "PetStore-" + n, false);
    admin.purgeQueue(Queues.JOBS_QUEUE, true);
    admin.purgeQueue(Queues.DLQ, true);
  }

  @Test
  void redeliveredRunMessage_opensOnePr() {
    GenerationJob job = new GenerationJob(runId, 0);
    publish(job);
    publish(job);

    awaitTerminal();
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> fakes.vcs().calls("openPr").size() == 1);

    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
    assertThat(sdkVersions.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize(1);
  }

  @Test
  void transientFailure_retriesWithNextAttempt() {
    AtomicInteger lintCalls = new AtomicInteger();
    fakes.on(
        LINT_SPEC,
        ctx -> {
          if (lintCalls.incrementAndGet() == 1) {
            throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
          }
          return new StepResult.Continue("LINT_SPEC-out");
        });

    publish(new GenerationJob(runId, 0));

    awaitTerminal();
    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(run.getAttempt()).isEqualTo(1);
    assertThat(lintCalls).hasValue(2);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
  }

  @Test
  void threeTransientFailures_goToDlq_andFail() {
    AtomicInteger lintCalls = new AtomicInteger();
    fakes.on(
        LINT_SPEC,
        ctx -> {
          lintCalls.incrementAndGet();
          throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
        });

    publish(new GenerationJob(runId, 0));

    await().atMost(Duration.ofSeconds(20)).until(() -> reload().getStatus() == RunStatus.FAILED);
    GenerationRun run = reload();
    assertThat(run.getFailureCode()).isEqualTo("STORAGE_ERROR");
    assertThat(run.getFailureMessage()).isEqualTo("Gave up after 3 attempts: blip");
    assertThat(run.getFinishedAt()).isNotNull();
    assertThat(run.getAttempt()).isEqualTo(3);
    // The first delivery and three retries: the fourth failure is the one that dead-letters.
    assertThat(lintCalls).hasValue(4);
    assertThat(rabbit.receiveAndConvert(Queues.DLQ, 5000)).isEqualTo(new GenerationJob(runId, 3));
  }

  @Test
  void cancelDuringSandbox_killsContainerAndCleansBranch() {
    // The branch a run cuts before it commits, so a cancel has something to clean up.
    fakes.vcs().createBranch(repo, branch, fakes.vcs().branchHead(repo, "main"));
    updateRun(r -> r.setBranchName(branch));
    AtomicBoolean killed = new AtomicBoolean();
    fakes.on(SYNTAX_CHECK, ctx -> blockUntilCancelled(ctx, killed));

    publish(new GenerationJob(runId, 0));
    await().atMost(Duration.ofSeconds(10)).until(() -> reload().getCurrentStep() == SYNTAX_CHECK);
    assertThat(fakes.vcs().branches(repo)).contains(branch);

    updateRun(r -> r.setCancelRequested(true));
    rabbit.convertAndSend(Queues.CONTROL_EXCHANGE, "", new ControlMessage("CANCEL", runId));

    await().atMost(Duration.ofSeconds(10)).until(() -> reload().getStatus() == RunStatus.CANCELLED);
    assertThat(killed).isTrue();
    assertThat(fakes.vcs().branches(repo)).doesNotContain(branch);
    assertThat(reload().getFinishedAt()).isNotNull();
  }

  @Test
  void concurrentDeliveries_onlyOneWorkerRuns() {
    AtomicInteger fetches = new AtomicInteger();
    fakes.on(
        FETCH_SPEC,
        ctx -> {
          fetches.incrementAndGet();
          // Long enough that the second delivery is consumed while this run still holds its lock.
          Thread.sleep(1000);
          return new StepResult.Continue("FETCH_SPEC-out");
        });

    GenerationJob job = new GenerationJob(runId, 0);
    publish(job);
    publish(job);

    awaitTerminal();
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> fakes.vcs().calls("openPr").size() == 1);

    assertThat(fetches).hasValue(1);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
  }

  private static StepResult blockUntilCancelled(RunContext ctx, AtomicBoolean killed)
      throws InterruptedException {
    try {
      while (true) {
        ctx.checkCancelled();
        Thread.sleep(POLL);
      }
    } catch (CancelledException e) {
      // The real sandbox step kills its container at this point.
      killed.set(true);
      throw e;
    }
  }

  private void publish(GenerationJob job) {
    rabbit.convertAndSend(Queues.JOBS_EXCHANGE, Queues.JOBS_ROUTING_KEY, job);
  }

  private void awaitTerminal() {
    await().atMost(Duration.ofSeconds(20)).until(() -> reload().getStatus().isTerminal());
  }

  private GenerationRun reload() {
    return runs.findById(runId).orElseThrow();
  }

  private void updateRun(Consumer<GenerationRun> change) {
    GenerationRun run = reload();
    change.accept(run);
    runs.saveAndFlush(run);
  }

  /** What a fake step does when it runs. */
  interface Behaviour {
    StepResult run(RunContext ctx) throws Exception;
  }

  /** The fakes the beans share: per-step behaviour and the VCS client. Reset before each test. */
  static final class Fakes {

    private final Map<StepName, Behaviour> behaviour = new ConcurrentHashMap<>();
    private volatile InMemoryVcsClient vcs = new InMemoryVcsClient();

    void reset() {
      behaviour.clear();
      vcs = new InMemoryVcsClient();
    }

    void on(StepName step, Behaviour next) {
      behaviour.put(step, next);
    }

    InMemoryVcsClient vcs() {
      return vcs;
    }

    /** The fake step for {@code name}: its behaviour if set, otherwise a default that continues. */
    PipelineStep step(StepName name) {
      return new PipelineStep() {
        @Override
        public StepName name() {
          return name;
        }

        @Override
        public StepResult execute(RunContext ctx) throws Exception {
          Behaviour next = behaviour.get(name);
          return next != null ? next.run(ctx) : defaultResult(name, ctx);
        }
      };
    }

    private StepResult defaultResult(StepName name, RunContext ctx) {
      return switch (name) {
        case RUN_GENERATORS ->
            new StepResult.Continue(new RunGeneratorsStep.Output(0, "generation"));
        case CALC_VERSION -> new StepResult.Continue(new CalcVersionStep.Output("1.2.0", true));
        case COMMIT_OUTPUT -> {
          // What the real step does: cut the branch and open the PR.
          String branchName = "emitkit/run-" + ctx.run().getId();
          String repoName = ctx.project().getRepoFullName();
          VcsClient client = ctx.vcs();
          client.createBranch(repoName, branchName, client.branchHead(repoName, "main"));
          client.openPr(repoName, branchName, "main", "Update SDK", "body");
          yield new StepResult.Continue(COMMIT_OUTPUT.name() + "-out");
        }
        default -> new StepResult.Continue(name.name() + "-out");
      };
    }
  }

  /**
   * Boots JPA, Flyway and RabbitMQ with the worker's topology and listeners, and fake steps. Not a
   * {@code @SpringBootConfiguration}: RabbitTopologyIT, in this package, would find it when it
   * searches for its own configuration.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  @EnableConfigurationProperties(WorkerProperties.class)
  @Import({
    RabbitTopologyConfig.class,
    GenerationJobListener.class,
    ControlListener.class,
    RetryRouter.class,
    CancelCleanup.class,
    RunLock.class,
    CancellationRegistry.class,
    RunLoggerFactory.class
  })
  static class TestApp {

    @Bean
    Fakes fakes() {
      return new Fakes();
    }

    @Bean
    VcsProvider vcsProvider(Fakes fakes) {
      return (VcsProvider)
          Proxy.newProxyInstance(
              VcsProvider.class.getClassLoader(),
              new Class<?>[] {VcsProvider.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "forProject" -> {
                    return fakes.vcs();
                  }
                  case "hashCode" -> {
                    return System.identityHashCode(proxy);
                  }
                  case "equals" -> {
                    return proxy == args[0];
                  }
                  case "toString" -> {
                    return "fake VcsProvider";
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                }
              });
    }

    @Bean
    WorkDir workDir() throws IOException {
      return new WorkDir(Files.createTempDirectory("wrk11-"));
    }

    @Bean
    PipelineRunner pipelineRunner(
        Fakes fakes,
        GenerationRunRepository runs,
        ProjectRepository projects,
        ProjectConfigRepository configs,
        VcsProvider vcsProvider,
        PlatformTransactionManager transactions,
        CancellationRegistry cancellation,
        RunLoggerFactory loggers,
        WorkDir workDir,
        SdkVersionRepository sdkVersions) {
      List<PipelineStep> steps = new ArrayList<>();
      for (StepName name : StepName.values()) {
        steps.add(
            name == StepName.FINALIZE
                ? new FinalizeStep(workDir, runs, sdkVersions, transactions)
                : fakes.step(name));
      }
      return new PipelineRunner(
          steps,
          runs,
          projects,
          configs,
          vcsProvider,
          transactions,
          cancellation,
          loggers,
          workDir);
    }
  }
}
