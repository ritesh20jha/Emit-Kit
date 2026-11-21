package dev.emitkit.worker.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.emitkit.common.id.Ids;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.Decision;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OAuthAccount;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunApproval;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.S3ArtifactStorage;
import dev.emitkit.domain.storage.StorageProperties;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.worker.WorkerApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole pipeline against real infrastructure: PostgreSQL, RabbitMQ, Redis, Vault (Transit),
 * RustFS, the toolchain image the sandbox runs, and a {@link GitHubMock}. The worker runs
 * in-process. Skipped when the toolchain image is missing, unless {@code e2e.required} is set.
 */
@SpringBootTest(
    classes = WorkerApplication.class,
    properties = {"emitkit.worker.pipeline.enabled=true", "spring.flyway.enabled=true"})
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@EnabledIf("toolchainAvailable")
class WRK12PipelineE2EIT {

  static final String TOOLCHAIN =
      System.getProperty("emitkit.toolchain.image", "emitkit/toolchain:test");
  private static final String GITHUB_TOKEN = "gho_e2e_token";
  private static final String VAULT_ROOT = "dev-root";
  private static final String S3_SECRET = "emitkit-secret";
  private static final String BUCKET = "emitkit-artifacts";
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final GitHubMock GITHUB = new GitHubMock(GITHUB_TOKEN);
  private static Path secretIdFile;

  /** A project and its config, as {@link #seedProject} saves them. */
  record Seeded(Project project, ProjectConfig config) {}

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> VAULT =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:2.2.1"))
          .withCommand(
              "server",
              "-dev",
              "-dev-root-token-id=" + VAULT_ROOT,
              "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

  @Container
  static final GenericContainer<?> RUSTFS =
      new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.4"))
          .withEnv("RUSTFS_ACCESS_KEY", "emitkit")
          .withEnv("RUSTFS_SECRET_KEY", S3_SECRET)
          .withEnv("RUSTFS_VOLUMES", "/data")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/health").forPort(9000));

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private OAuthAccountRepository accounts;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private RunArtifactRepository artifacts;
  @Autowired private RunApprovalRepository approvals;
  @Autowired private SdkVersionRepository sdkVersions;
  @Autowired private SecretCipher cipher;
  @Autowired private ArtifactStorage storage;
  @Autowired private RabbitTemplate rabbit;

  /** True when the toolchain image is present. Fails instead when {@code e2e.required} is set. */
  static boolean toolchainAvailable() throws InterruptedException {
    try {
      var inspect =
          new ProcessBuilder("docker", "image", "inspect", TOOLCHAIN)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (inspect.waitFor() == 0) {
        return true;
      }
    } catch (IOException e) {
      // No docker binary: the image is not there either.
    }
    if (Boolean.getBoolean("e2e.required")) {
      throw new IllegalStateException("e2e.required is set but " + TOOLCHAIN + " is missing");
    }
    return false;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    String roleId = provisionVault();
    registry.add(
        "emitkit.vault.uri", () -> "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    registry.add("emitkit.vault.role-id", () -> roleId);
    registry.add("emitkit.vault.secret-id-file", () -> secretIdFile.toString());
    registry.add("emitkit.storage.endpoint", WRK12PipelineE2EIT::rustfsUrl);
    registry.add("emitkit.storage.bucket", () -> BUCKET);
    registry.add("emitkit.storage.secret-key", () -> S3_SECRET);
    registry.add("emitkit.github.api-url", GITHUB::apiUrl);
    registry.add("emitkit.sandbox.runtime", () -> System.getProperty("sandbox.runtime", "runc"));
    registry.add(
        "emitkit.sandbox.docker-host",
        () -> System.getProperty("sandbox.docker-host", "unix:///var/run/docker.sock"));
    registry.add("emitkit.sandbox.image", () -> TOOLCHAIN);
  }

  /**
   * Enables Transit (the key the worker uses) and AppRole in the dev Vault, writes the worker's
   * secret id to a file, and returns the role id.
   */
  private static String provisionVault() throws IOException, InterruptedException {
    String script =
        """
        set -e
        export VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=%s
        vault secrets enable transit >/dev/null
        vault write -f transit/keys/emitkit-secrets type=aes256-gcm96 derived=true exportable=false >/dev/null
        vault auth enable approle >/dev/null
        echo 'path "transit/+/emitkit-secrets" { capabilities = ["update"] }' > /tmp/worker.hcl
        vault policy write emitkit-worker /tmp/worker.hcl >/dev/null
        vault write auth/approle/role/emitkit-worker token_policies=emitkit-worker >/dev/null
        echo "$(vault read -field=role_id auth/approle/role/emitkit-worker/role-id) $(vault write -f -field=secret_id auth/approle/role/emitkit-worker/secret-id)"
        """
            .formatted(VAULT_ROOT);
    var result = VAULT.execInContainer("sh", "-c", script);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("vault setup failed: " + result.getStderr());
    }
    String[] ids = result.getStdout().trim().split("\\s+");
    secretIdFile = Files.createTempFile("e2e-secret-id", "");
    secretIdFile.toFile().deleteOnExit();
    Files.writeString(secretIdFile, ids[1]);
    return ids[0];
  }

  @BeforeAll
  static void createBucket() {
    var props = new StorageProperties(rustfsUrl(), BUCKET, "emitkit", S3_SECRET, "us-east-1");
    try (var s3 = S3ArtifactStorage.createClient(props)) {
      s3.createBucket(request -> request.bucket(BUCKET));
    }
  }

  @AfterAll
  static void stopGitHub() {
    GITHUB.close();
  }

  private static String rustfsUrl() {
    return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
  }

  @Test
  void petstoreAllOutputs_success_prOpened_artifactStored() throws IOException {
    GenerationRun run = runToEnd(FixtureSpecs.raw("petstore"));
    String repo = projects.findById(run.getProjectId()).orElseThrow().getRepoFullName();

    assertThat(run.getStatus())
        .as("run failed: %s %s", run.getFailureCode(), run.getFailureMessage())
        .isEqualTo(RunStatus.SUCCESS);
    assertThat(GITHUB.unhandled()).as("requests the GitHub mock could not serve").isEmpty();

    assertThat(GITHUB.pulls(repo)).hasSize(1);
    GitHubMock.Pull pull = GITHUB.pulls(repo).get(0);
    assertThat(run.getPrNumber()).isEqualTo(pull.number());
    assertThat(run.getPrUrl()).isEqualTo("https://github.com/" + repo + "/pull/" + pull.number());
    assertThat(GITHUB.files(repo, pull.head())).containsKey(".emitkit/sdk/typescript/package.json");

    RunArtifact artifact = artifacts.findByRunId(run.getId()).orElseThrow();
    assertThat(artifact.getFileCount()).isPositive();
    assertThat(artifact.getSizeBytes()).isPositive();
    try (InputStream bytes = storage.get(artifact.getStorageKey())) {
      assertThat(bytes.readAllBytes()).hasSize((int) artifact.getSizeBytes());
    }

    assertThat(run.getSdkVersion()).isNotBlank();
    assertThat(sdkVersions.findByProjectIdOrderByCreatedAtDesc(run.getProjectId()))
        .extracting(SdkVersion::getVersion)
        .containsExactly(run.getSdkVersion());
  }

  @Test
  void secondRunWithNoSpecChange_versionUnchanged_noNewPr() {
    Seeded seeded = seedProject(FixtureSpecs.raw("petstore"), config -> {});
    String repo = seeded.project().getRepoFullName();
    GenerationRun first = awaitTerminal(enqueue(seeded).getId());
    assertThat(first.getStatus()).as("first run").isEqualTo(RunStatus.SUCCESS);
    // Merging the first pull request puts its files on the target branch, as a user would.
    GitHubMock.Pull pull = GITHUB.pulls(repo).get(0);
    GITHUB
        .files(repo, pull.head())
        .forEach((path, content) -> GITHUB.commit(repo, "main", path, content));

    GenerationRun second = awaitTerminal(enqueue(seeded).getId());

    assertThat(second.getStatus())
        .as("second run failed: %s %s", second.getFailureCode(), second.getFailureMessage())
        .isEqualTo(RunStatus.SUCCESS);
    assertThat(second.getSdkVersion()).isEqualTo(first.getSdkVersion());
    assertThat(second.getPrNumber()).isNull();
    assertThat(GITHUB.pulls(repo)).hasSize(1);
    assertThat(GITHUB.unhandled()).as("requests the GitHub mock could not serve").isEmpty();
  }

  @Test
  void breakingChange_withRequireApproval_parksThenCompletesAfterApproval() {
    Seeded seeded =
        seedProject(
            FixtureSpecs.raw("petstore"),
            config -> config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL));
    String repo = seeded.project().getRepoFullName();
    GenerationRun first = awaitTerminal(enqueue(seeded).getId());
    assertThat(first.getStatus()).as("first run").isEqualTo(RunStatus.SUCCESS);

    // Making the query parameter required is a breaking change to GET /pets.
    String v1 = FixtureSpecs.raw("petstore");
    String v2 =
        v1.replace(
            "{name: limit, in: query, schema:", "{name: limit, in: query, required: true, schema:");
    assertThat(v2).as("v2 differs from v1").isNotEqualTo(v1);
    GITHUB.commit(repo, "main", "openapi.yaml", v2);

    GenerationRun run = enqueue(seeded);
    GenerationRun parked = awaitStatus(run.getId(), RunStatus.AWAITING_APPROVAL);
    assertThat(parked.getPrNumber()).as("no pull request before approval").isNull();
    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(approval -> assertThat(approval.getDecision()).isNull());

    // Records the approval as the approve endpoint does, then queues the run again.
    RunApproval approval = approvals.findByRunId(run.getId()).orElseThrow();
    approval.decide(seeded.project().getCreatedBy(), Decision.APPROVED, null, Instant.now());
    approvals.save(approval);
    GenerationRun approved = runs.findById(run.getId()).orElseThrow();
    approved.setStatus(RunStatus.QUEUED);
    runs.save(approved);
    publish(run.getId());

    GenerationRun done = awaitTerminal(run.getId());
    assertThat(done.getStatus())
        .as("run failed: %s %s", done.getFailureCode(), done.getFailureMessage())
        .isEqualTo(RunStatus.SUCCESS);
    assertThat(GITHUB.pulls(repo)).hasSize(2);
    assertThat(done.getPrNumber()).isEqualTo(2);
    assertThat(GITHUB.comments(repo, 2))
        .singleElement()
        .asString()
        .contains("breaking API changes");
    assertThat(GITHUB.unhandled()).as("requests the GitHub mock could not serve").isEmpty();
  }

  @Test
  void big200Ops_finishesUnder3Minutes() {
    GenerationRun run =
        assertTimeoutPreemptively(
            Duration.ofMinutes(3), () -> runToEnd(FixtureSpecs.raw("big-200-ops")));
    String repo = projects.findById(run.getProjectId()).orElseThrow().getRepoFullName();

    assertThat(run.getStatus())
        .as("run failed: %s %s", run.getFailureCode(), run.getFailureMessage())
        .isEqualTo(RunStatus.SUCCESS);
    assertThat(GITHUB.pulls(repo)).hasSize(1);
    assertThat(run.getPrNumber()).isEqualTo(1);
    assertThat(GITHUB.unhandled()).as("requests the GitHub mock could not serve").isEmpty();
  }

  /** Seeds one project, enqueues a run for it, and waits for the run to end. */
  GenerationRun runToEnd(String spec) {
    return awaitTerminal(enqueue(seedProject(spec, config -> {})).getId());
  }

  /**
   * Seeds a user with an encrypted GitHub token, an organization, a GitHub repo whose {@code
   * openapi.yaml} is {@code spec}, and a project. Its config has every output kind; {@code adjust}
   * edits the config before it is saved.
   */
  Seeded seedProject(String spec, Consumer<ProjectConfig> adjust) {
    long n = SEQUENCE.incrementAndGet();
    String login = "e2e" + n;
    var creator =
        users.saveAndFlush(
            new User(null, 9000 + n, login, login, login + "@example.com", "https://a"));
    String accountId = Ids.newUlid();
    accounts.saveAndFlush(
        new OAuthAccount(
            accountId,
            creator.getId(),
            "github",
            String.valueOf(creator.getGithubId()),
            cipher.encrypt(
                GITHUB_TOKEN, new SecretContext("oauth_accounts", "access_token_enc", accountId)),
            "repo"));
    var org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, login, login, false, creator.getId()));
    String repo = "acme/petstore-" + n;
    GITHUB.createRepo(repo, "main", Map.of("openapi.yaml", spec));
    var project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                repo,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    var config =
        new ProjectConfig(
            null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId());
    config.setOutputs(List.of(OutputKind.SDK, OutputKind.CLI, OutputKind.MCP, OutputKind.DOCS));
    config.setSdkLanguages(List.of("typescript", "python", "java"));
    config.setNpmScope("@acme");
    config.setPypiName("acme-petstore");
    config.setMavenGroupId("dev.acme");
    config.setMavenArtifactId("petstore");
    adjust.accept(config);
    return new Seeded(project, configs.saveAndFlush(config));
  }

  /** Creates a queued run for the seeded project and publishes its job. */
  GenerationRun enqueue(Seeded seeded) {
    var run =
        runs.saveAndFlush(
            new GenerationRun(
                null,
                seeded.project().getId(),
                seeded.config().getId(),
                RunTrigger.MANUAL,
                false,
                Instant.now()));
    publish(run.getId());
    return run;
  }

  /** Publishes a job for an existing run, as a new run or a redelivery does. */
  void publish(String runId) {
    rabbit.convertAndSend(
        Queues.JOBS_EXCHANGE, Queues.JOBS_ROUTING_KEY, new GenerationJob(runId, 0));
  }

  /** Waits until the run reaches a terminal status, then returns it. */
  GenerationRun awaitTerminal(String runId) {
    return await()
        .atMost(Duration.ofMinutes(5))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> runs.findById(runId).orElseThrow(), run -> run.getStatus().isTerminal());
  }

  /** Waits until the run is in {@code status}, then returns it. */
  GenerationRun awaitStatus(String runId, RunStatus status) {
    return await()
        .atMost(Duration.ofMinutes(2))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> runs.findById(runId).orElseThrow(), run -> run.getStatus() == status);
  }
}
