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
import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
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
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import dev.emitkit.worker.steps.CalcVersionStep;
import dev.emitkit.worker.steps.FinalizeStep;
import dev.emitkit.worker.steps.RunGeneratorsStep;
import dev.emitkit.worker.steps.WorkDir;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.sql.DataSource;
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
import org.springframework.dao.DataAccessResourceFailureException;
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

/** The job listener against RabbitMQ, PostgreSQL and Redis, with fake steps. */
@SpringBootTest(
    classes = WRK11RetryCancelIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
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
  @Autowired private GenerationRunLogRepository logs;
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
    awaitSingleOpenPr();

    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
    assertThat(sdkVersions.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize(1);
  }

  @Test
  void transientFailure_retriesWithNextAttempt() {
    AtomicInteger lintCalls = new AtomicInteger();
    AtomicReference<RunLogger> firstLog = new AtomicReference<>();
    fakes.on(
        LINT_SPEC,
        ctx -> {
          int call = lintCalls.incrementAndGet();
          ctx.log().info("lint attempt " + call);
          if (call == 1) {
            firstLog.set(ctx.log());
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
    // Both attempts' lines and the retry notice are stored, in one unbroken seq run.
    awaitLogLine("Retrying in 200ms");
    assertThat(logLines())
        .extracting(GenerationRunLog::getMessage)
        .contains("lint attempt 1", "Retrying in 200ms", "lint attempt 2");
    assertSeqContiguous();
    assertFlushStopped(firstLog.get());
  }

  @Test
  void unexpectedException_failsTheRunAsInternal_andKeepsItsDetailOut() {
    AtomicReference<RunLogger> logger = new AtomicReference<>();
    fakes.on(
        LINT_SPEC,
        ctx -> {
          logger.set(ctx.log());
          ctx.log().info("before the crash");
          throw new RuntimeException("secret detail");
        });

    publish(new GenerationJob(runId, 0));

    awaitTerminal();
    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo("INTERNAL");
    assertThat(run.getFailureMessage()).isEqualTo("Internal error");
    assertThat(run.getFinishedAt()).isNotNull();
    // FAILED is committed before the failure's own log lines are saved, so wait for the last one.
    awaitLogLine("Internal error");
    assertThat(logLines())
        .extracting(GenerationRunLog::getMessage)
        .contains("before the crash", "Internal error")
        .noneMatch(message -> message.contains("secret detail"));
    assertSeqContiguous();
    assertFlushStopped(logger.get());
  }

  @Test
  void exceptionEscapingOnJob_isNackedAndRunCompletesOnRedelivery() {
    // The first delivery's lock attempt fails; the second finds the lock free.
    fakes.failNextLock(runId);

    publish(new GenerationJob(runId, 0));

    // Requeued only after the 5 s pause, so the run cannot finish inside it.
    await()
        .atLeast(Duration.ofSeconds(4))
        .atMost(Duration.ofSeconds(20))
        .until(() -> reload().getStatus().isTerminal());
    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
  }

  @Test
  void fourthTransientFailure_goesToDlq_andFails() {
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
    assertThat(run.getFailureMessage()).isEqualTo("Gave up after 3 retries: blip");
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
    AtomicReference<RunLogger> logger = new AtomicReference<>();
    fakes.on(
        SYNTAX_CHECK,
        ctx -> {
          logger.set(ctx.log());
          ctx.log().info("syntax check started");
          return blockUntilCancelled(ctx, killed);
        });

    publish(new GenerationJob(runId, 0));
    await().atMost(Duration.ofSeconds(10)).until(() -> reload().getCurrentStep() == SYNTAX_CHECK);
    assertThat(fakes.vcs().branches(repo)).contains(branch);

    // Only the control message, DB flag unset: the listener must act before the 5 s DB recheck.
    rabbit.convertAndSend(Queues.CONTROL_EXCHANGE, "", new ControlMessage("CANCEL", runId));

    await().atMost(Duration.ofSeconds(3)).until(() -> reload().getStatus() == RunStatus.CANCELLED);
    assertThat(reload().isCancelRequested()).isFalse();
    assertThat(killed).isTrue();
    assertThat(fakes.vcs().branches(repo)).doesNotContain(branch);
    assertThat(reload().getFinishedAt()).isNotNull();
    assertSeqContiguous();
    assertFlushStopped(logger.get());
  }

  @Test
  void concurrentDeliveries_onlyOneWorkerRuns() {
    AtomicInteger fetches = new AtomicInteger();
    fakes.on(
        FETCH_SPEC,
        ctx -> {
          fetches.incrementAndGet();
          // Long enough for the second delivery to arrive while this run holds the lock.
          Thread.sleep(1000);
          return new StepResult.Continue("FETCH_SPEC-out");
        });

    GenerationJob job = new GenerationJob(runId, 0);
    publish(job);
    publish(job);

    awaitTerminal();
    awaitSingleOpenPr();

    assertThat(fetches).hasValue(1);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
  }

  @Test
  void lockHeldByAnotherSession_reparksTheJob_andRunsAfterRelease() throws Exception {
    try (Connection holder = holdLock()) {
      publish(new GenerationJob(runId, 0));

      // The job is parked, not dropped: the run stays queued while the lock is held elsewhere.
      await()
          .during(Duration.ofSeconds(1))
          .atMost(Duration.ofSeconds(3))
          .until(() -> reload().getStatus() == RunStatus.QUEUED);
      releaseLock(holder);
    }

    awaitTerminal();
    assertThat(reload().getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(fakes.vcs().calls("openPr")).hasSize(1);
  }

  @Test
  void lockHeldForFinishedRun_isDroppedNotReparked() throws Exception {
    updateRun(r -> r.setStatus(RunStatus.SUCCESS));

    try (Connection holder = holdLock()) {
      publish(new GenerationJob(runId, 0));

      // Acked and dropped: nothing sits in the retry queue or comes back to the jobs queue.
      await()
          .during(Duration.ofSeconds(1))
          .atMost(Duration.ofSeconds(3))
          .until(
              () -> queueDepth(Queues.retryQueueFor(1)) == 0 && queueDepth(Queues.JOBS_QUEUE) == 0);
      releaseLock(holder);
    }
  }

  @Test
  void staleJob_countsRetriesFromTheRun() {
    // The run has already used three retries; a redelivered attempt-0 job must not get more.
    updateRun(r -> r.setAttempt(3));
    AtomicInteger lintCalls = new AtomicInteger();
    fakes.on(
        LINT_SPEC,
        ctx -> {
          lintCalls.incrementAndGet();
          throw new TransientException(ErrorCode.STORAGE_ERROR, "blip");
        });

    publish(new GenerationJob(runId, 0));

    await().atMost(Duration.ofSeconds(10)).until(() -> reload().getStatus() == RunStatus.FAILED);
    assertThat(lintCalls).hasValue(1);
    assertThat(reload().getFailureMessage()).isEqualTo("Gave up after 3 retries: blip");
    assertThat(reload().getAttempt()).isEqualTo(3);
    assertThat(rabbit.receiveAndConvert(Queues.DLQ, 5000)).isEqualTo(new GenerationJob(runId, 0));
  }

  /** A second session that holds the run's advisory lock, as another worker's delivery would. */
  private Connection holdLock() throws SQLException {
    Connection holder =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (PreparedStatement lock = holder.prepareStatement("select pg_advisory_lock(hashtext(?))")) {
      lock.setString(1, runId);
      lock.execute();
    }
    return holder;
  }

  private void releaseLock(Connection holder) throws SQLException {
    try (PreparedStatement unlock =
        holder.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
      unlock.setString(1, runId);
      unlock.execute();
    }
  }

  private long queueDepth(String queue) {
    return ((Number) admin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT))
        .longValue();
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

  /** Waits out a second delivery's window, then checks that only one PR was opened. */
  private void awaitSingleOpenPr() {
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> fakes.vcs().calls("openPr").size() == 1);
  }

  private List<GenerationRunLog> logLines() {
    return logs.findByRunIdAndSeqGreaterThanOrderBySeq(runId, 0);
  }

  /** Waits until a line with exactly {@code message} is saved; the status can commit before it. */
  private void awaitLogLine(String message) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> logLines().stream().anyMatch(line -> message.equals(line.getMessage())));
  }

  /** The saved lines carry seqs 1..n in order: no duplicates and no gaps. */
  private void assertSeqContiguous() {
    List<Integer> seqs = logLines().stream().map(GenerationRunLog::getSeq).toList();
    assertThat(seqs).isNotEmpty().isEqualTo(IntStream.rangeClosed(1, seqs.size()).boxed().toList());
  }

  /**
   * A line written to a logger after its run exited is never saved: that logger's periodic flush
   * has stopped. The flush interval is two seconds, so three seconds covers one more flush.
   */
  private void assertFlushStopped(RunLogger closed) {
    // A probe line takes a seq the run may already hold, and the unique (run_id, seq) rejects that
    // one. Four lines leave at least one seq past the run's last line, and that one is saved if the
    // flush still runs. This relies on the run writing fewer than four lines after the closed
    // logger's seq; the tests here write at most two.
    for (int i = 0; i < 4; i++) {
      closed.info("written after close " + i);
    }
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(4))
        .until(() ->
            logLines().stream()
                .noneMatch(line -> line.getMessage().startsWith("written after close")));
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
    private final Set<String> failingLocks = ConcurrentHashMap.newKeySet();
    private volatile InMemoryVcsClient vcs = new InMemoryVcsClient();

    void reset() {
      behaviour.clear();
      failingLocks.clear();
      vcs = new InMemoryVcsClient();
    }

    void on(StepName step, Behaviour next) {
      behaviour.put(step, next);
    }

    /** Makes the next lock attempt for {@code runId} throw, as a lost database connection would. */
    void failNextLock(String runId) {
      failingLocks.add(runId);
    }

    boolean takeLockFailure(String runId) {
      return failingLocks.remove(runId);
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
   * Boots the listeners against the containers. Not @SpringBootConfiguration: see RabbitTopologyIT.
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
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "forProject" -> fakes.vcs();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fake VcsProvider";
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }

    /** The real run lock, except that a run marked by {@link Fakes#failNextLock} fails once. */
    @Bean
    RunLock runLock(DataSource dataSource, Fakes fakes) {
      return new RunLock(dataSource) {
        @Override
        Optional<RunLock.Lease> tryAcquire(String runId) {
          if (fakes.takeLockFailure(runId)) {
            throw new DataAccessResourceFailureException("db blip");
          }
          return super.tryAcquire(runId);
        }
      };
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
