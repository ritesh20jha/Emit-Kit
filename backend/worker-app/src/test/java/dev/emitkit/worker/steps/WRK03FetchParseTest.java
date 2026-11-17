package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.FETCH_SPEC;
import static dev.emitkit.domain.StepName.PARSE_SPEC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.VcsProviderId;
import dev.emitkit.vcs.WebhookVerifier;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.error.VcsServerException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.PipelineRunner;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepOutputs;
import dev.emitkit.worker.pipeline.StepResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * FETCH_SPEC and PARSE_SPEC run through the real {@link PipelineRunner} against PostgreSQL 18 and
 * an in-memory VCS. The other nine steps are scripted no-ops.
 */
@SpringBootTest(
    classes = WRK03FetchParseTest.TestApp.class,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "emitkit.vault.cipher=local"})
@Testcontainers
class WRK03FetchParseTest {

  private static final String BRANCH = "main";
  private static final String SPEC_PATH = "openapi.yaml";
  private static final String REVOKED =
      "The project creator's GitHub access no longer works. An admin must re-link the project.";
  private static final String SPEC =
      """
      openapi: 3.0.3
      info:
        title: Pets
        version: 1.0.0
      paths:
        /pets:
          get:
            operationId: listPets
            responses:
              '200':
                description: ok
      """;
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private PlatformTransactionManager transactions;

  private final InMemoryVcsClient vcs = new InMemoryVcsClient();
  private User creator;
  private Organization org;
  private String repo;
  private String projectId;
  private String configId;
  private String runId;

  @BeforeEach
  void seedCreatorAndRepo() {
    long n = SEQUENCE.incrementAndGet();
    creator =
        users.saveAndFlush(
            new User(null, n, "user" + n, "User " + n, "user" + n + "@example.com", "https://a"));
    org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, "org" + n, "Org " + n, false, null));
    repo = "acme/api-" + n;
    vcs.createRepo("acme", "api-" + n, false);
  }

  @Test
  void tooLarge_failsSpecTooLarge() {
    queueRun(repo, SPEC_PATH);
    // "é" is two bytes in UTF-8, so the content is over 2 MB in bytes though it has fewer chars.
    String head = commit(SPEC_PATH, "é".repeat(1024 * 1024 + 1));

    runner().run(runId);

    GenerationRun run = reload();
    assertFailed(run, "SPEC_TOO_LARGE", "spec is larger than 2 MB");
    assertThat(run.getCommitSha()).isEqualTo(head);
  }

  @Test
  void badRepoName_failsSpecNotFound() {
    queueRun("not a repo", SPEC_PATH);

    runner().run(runId);

    assertFailed(reload(), "SPEC_NOT_FOUND", "invalid repository name: not a repo");
    // The name is rejected before any read of the provider.
    assertThat(vcs.calls("branchHead")).isEmpty();
    assertThat(vcs.calls("getFile")).isEmpty();
  }

  @Test
  void missingFile_failsSpecNotFound() {
    queueRun(repo, "missing.yaml");
    commit(SPEC_PATH, SPEC);

    runner().run(runId);

    assertFailed(reload(), "SPEC_NOT_FOUND", "spec not found: missing.yaml on " + BRANCH);
  }

  @Test
  void revokedToken_failsWithVcsAuth_noRetry() {
    queueRun(repo, SPEC_PATH);
    commit(SPEC_PATH, SPEC);
    vcs.failNext(new VcsAuthException("bad credentials"));
    PipelineRunner runner = runner();

    // The VCS_AUTH failure ends the run in the runner's own write. Nothing is rethrown for retry.
    assertThatCode(() -> runner.run(runId)).doesNotThrowAnyException();

    GenerationRun run = reload();
    assertFailed(run, "VCS_AUTH", REVOKED);
    assertThat(run.getCurrentStep()).isEqualTo(FETCH_SPEC);
  }

  @Test
  void serverError_isTransient() {
    queueRun(repo, SPEC_PATH);
    commit(SPEC_PATH, SPEC);
    vcs.failNext(new VcsServerException("GitHub returned 502"));
    PipelineRunner runner = runner();

    assertThatThrownBy(() -> runner.run(runId))
        .isInstanceOf(VcsServerException.class)
        .isInstanceOf(TransientException.class);

    GenerationRun run = reload();
    assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.getFailureCode()).isNull();
    assertThat(run.getCurrentStep()).isEqualTo(FETCH_SPEC);
    assertThat(StepOutputs.read(run.getStepOutputs(), FETCH_SPEC, Map.class)).isEmpty();
  }

  @Test
  void snapshotReusedWhenHashMatches() {
    queueRun(repo, SPEC_PATH);
    commit(SPEC_PATH, SPEC);
    String hash = new SpecParser().parse(SPEC).contentHash();
    // A stored snapshot with the same hash. Its other columns are marked so a rewrite would show.
    SpecSnapshot stored =
        snapshots.saveAndFlush(
            new SpecSnapshot(null, projectId, hash, SPEC, "{}", "stored-marker"));

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getSpecSnapshotId()).isEqualTo(stored.getId());
    assertThat(snapshots.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize(1);
    assertThat(snapshots.findById(stored.getId()).orElseThrow().getInfoVersion())
        .isEqualTo("stored-marker");
    assertThat(StepOutputs.read(run.getStepOutputs(), PARSE_SPEC, Map.class))
        .hasValue(Map.of("snapshotId", stored.getId()));
  }

  @Test
  void invalidSpec_failsSpecInvalid() {
    queueRun(repo, SPEC_PATH);
    commit(SPEC_PATH, unresolvedRefs(7));

    runner().run(runId);

    // Seven unresolved refs are found, and only the first five are reported.
    String first5 =
        IntStream.range(0, 5)
            .mapToObj(i -> "unresolved $ref: #/components/schemas/Missing" + i)
            .collect(Collectors.joining("; "));
    assertFailed(reload(), "SPEC_INVALID", first5);
  }

  @Test
  void resumeWithoutContent_reusesStoredSnapshot_withoutFetchingOrParsing() {
    // The crash-resume state: the snapshot is stored, the run points at it, and the FETCH output
    // has been trimmed to its commit. PARSE_SPEC has no output yet.
    queueRun(repo, SPEC_PATH);
    String head = commit(SPEC_PATH, SPEC);
    SpecSnapshot stored =
        snapshots.saveAndFlush(
            new SpecSnapshot(
                null,
                projectId,
                new SpecParser().parse(SPEC).contentHash(),
                SPEC,
                "{}",
                "stored-marker"));
    GenerationRun row = reload();
    row.setCommitSha(head);
    row.setSpecSnapshotId(stored.getId());
    row.setStepOutputs(
        StepOutputs.with(row.getStepOutputs(), FETCH_SPEC, Map.of("commitSha", head)));
    runs.saveAndFlush(row);
    int callsBefore = vcs.calls().size();

    runner().run(runId);

    assertThat(vcs.calls()).hasSize(callsBefore);
    GenerationRun run = reload();
    assertThat(run.getSpecSnapshotId()).isEqualTo(stored.getId());
    assertThat(StepOutputs.read(run.getStepOutputs(), PARSE_SPEC, Map.class))
        .hasValue(Map.of("snapshotId", stored.getId()));
    assertThat(snapshots.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize(1);
    assertThat(run.getCurrentStep()).isEqualTo(StepName.FINALIZE);
  }

  @Test
  void concurrentRunsWithSameContent_shareOneSnapshot() throws Exception {
    queueRun(repo, SPEC_PATH);
    commit(SPEC_PATH, SPEC);
    String otherRunId = queueAnotherRun();
    // Both runs look up the hash and find nothing before either one inserts. The barrier holds
    // each insert until the other run has done its lookup, so the race is forced, not timed.
    CyclicBarrier bothLookedUp = new CyclicBarrier(2);
    AtomicInteger inserts = new AtomicInteger();
    PipelineRunner runner = runner(racingSnapshots(bothLookedUp, inserts));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> runner.run(runId));
      Future<?> second = pool.submit(() -> runner.run(otherRunId));
      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    String snapshotId =
        snapshots
            .findByProjectIdAndContentHash(projectId, new SpecParser().parse(SPEC).contentHash())
            .orElseThrow()
            .getId();
    assertThat(snapshots.findByProjectIdOrderByCreatedAtDesc(projectId)).hasSize(1);
    assertThat(reload(runId).getSpecSnapshotId()).isEqualTo(snapshotId);
    assertThat(reload(otherRunId).getSpecSnapshotId()).isEqualTo(snapshotId);
    // Both runs inserted, so the second insert lost the race and was retried.
    assertThat(inserts.get()).isEqualTo(2);
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(reload(otherRunId).getStatus()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void fetchAndParse_storeSnapshot_andKeepOnlyTheCommitInFetchOutput() {
    queueRun(repo, SPEC_PATH);
    String head = commit(SPEC_PATH, SPEC);

    runner().run(runId);

    GenerationRun run = reload();
    assertThat(run.getCommitSha()).isEqualTo(head);
    assertThat(StepOutputs.read(run.getStepOutputs(), FETCH_SPEC, Map.class))
        .hasValue(Map.of("commitSha", head));
    SpecSnapshot snapshot = snapshots.findById(run.getSpecSnapshotId()).orElseThrow();
    assertThat(snapshot.getContentHash()).isEqualTo(new SpecParser().parse(SPEC).contentHash());
    assertThat(snapshot.getRawContent()).isEqualTo(SPEC);
    assertThat(snapshot.getInfoVersion()).isEqualTo("1.0.0");
    assertThat(ApiModelJson.fromJson(snapshot.getApiModel()).info().title()).isEqualTo("Pets");
    assertThat(StepOutputs.read(run.getStepOutputs(), PARSE_SPEC, Map.class))
        .hasValue(Map.of("snapshotId", snapshot.getId()));
    assertThat(run.getCurrentStep()).isEqualTo(StepName.FINALIZE);
  }

  /** Creates a project on {@code repoFullName} and a queued run for it. */
  private void queueRun(String repoFullName, String specPath) {
    Project project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                repoFullName,
                specPath,
                BRANCH,
                OutputMode.APPEND,
                creator.getId()));
    ProjectConfig config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
    projectId = project.getId();
    configId = config.getId();
    runId = queueAnotherRun();
  }

  /** Queues one more run of the project queued by {@link #queueRun}, and returns its id. */
  private String queueAnotherRun() {
    return runs.saveAndFlush(
            new GenerationRun(
                null,
                projectId,
                configId,
                RunTrigger.MANUAL,
                false,
                Instant.parse("2026-11-01T10:00:00Z")))
        .getId();
  }

  /** Commits one file on the default branch of the test repo and returns the new head sha. */
  private String commit(String path, String content) {
    return vcs.commitTree(repo, BRANCH, List.of(new CommitFile(path, content)), "test").sha();
  }

  /** A runner with the two real steps and scripted no-op steps for the other nine. */
  private PipelineRunner runner() {
    return runner(snapshots);
  }

  private PipelineRunner runner(SpecSnapshotRepository snapshotRepo) {
    List<PipelineStep> steps = new ArrayList<>();
    steps.add(new FetchSpecStep(runs, transactions));
    steps.add(new ParseSpecStep(runs, snapshotRepo, transactions));
    for (StepName name : StepName.values()) {
      if (name != FETCH_SPEC && name != PARSE_SPEC) {
        steps.add(noOp(name));
      }
    }
    return new PipelineRunner(
        steps, runs, projects, configs, provider(), transactions, new CancellationRegistry(runs));
  }

  private static PipelineStep noOp(StepName name) {
    return new PipelineStep() {
      @Override
      public StepName name() {
        return name;
      }

      @Override
      public StepResult execute(RunContext ctx) {
        return new StepResult.Continue(name.name() + "-out");
      }
    };
  }

  /** Hands the test's in-memory VCS to the runner, whatever the creator. */
  private VcsProvider provider() {
    return new VcsProvider() {
      @Override
      public VcsProviderId id() {
        return VcsProviderId.GITHUB;
      }

      @Override
      public VcsClient forUser(String accessToken) {
        throw new UnsupportedOperationException("forUser");
      }

      @Override
      public VcsClient forProject(String creatorUserId) {
        return vcs;
      }

      @Override
      public VcsClient forUser(String accessToken, String userId, RateLimitPolicy policy) {
        throw new UnsupportedOperationException("forUser");
      }

      @Override
      public VcsClient forProject(String creatorUserId, RateLimitPolicy policy) {
        throw new UnsupportedOperationException("forProject with policy");
      }

      @Override
      public WebhookVerifier webhookVerifier() {
        throw new UnsupportedOperationException("webhookVerifier");
      }
    };
  }

  private void assertFailed(GenerationRun run, String code, String message) {
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getFailureCode()).isEqualTo(code);
    assertThat(run.getFailureMessage()).isEqualTo(message);
  }

  private GenerationRun reload() {
    return reload(runId);
  }

  private GenerationRun reload(String id) {
    return runs.findById(id).orElseThrow();
  }

  /**
   * The snapshot repository with a barrier on each insert. The barrier waits for the other run's
   * lookup first. Counts the inserts, and unwraps reflective calls so the step sees the real
   * exception.
   */
  private SpecSnapshotRepository racingSnapshots(
      CyclicBarrier bothLookedUp, AtomicInteger inserts) {
    return (SpecSnapshotRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SpecSnapshotRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("save") || method.getName().equals("saveAndFlush")) {
                inserts.incrementAndGet();
                bothLookedUp.await(30, TimeUnit.SECONDS);
              }
              try {
                return method.invoke(snapshots, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** A document with {@code count} operations, each referring to a schema that does not exist. */
  private static String unresolvedRefs(int count) {
    StringBuilder yaml =
        new StringBuilder("openapi: 3.0.3\ninfo:\n  title: Pets\n  version: 1.0.0\npaths:\n");
    for (int i = 0; i < count; i++) {
      yaml.append("  /p")
          .append(i)
          .append(":\n    get:\n      responses:\n        '200':\n          description: ok\n")
          .append("          content:\n            application/json:\n              schema:\n")
          .append("                $ref: '#/components/schemas/Missing")
          .append(i)
          .append("'\n");
    }
    return yaml.toString();
  }

  /** Boots JPA and Flyway against the container, with the domain's entities and repositories. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
