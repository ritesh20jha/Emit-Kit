package dev.emitkit.worker.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.emitkit.common.id.Ids;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
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
 * RustFS (S3), the toolchain image the sandbox runs, and a {@link GitHubMock}. The worker runs
 * in-process. Skipped when the toolchain image is missing, unless {@code e2e.required} is set.
 */
@SpringBootTest(
    classes = WorkerApplication.class,
    properties = {"emitkit.worker.pipeline.enabled=true", "spring.flyway.enabled=true"})
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@EnabledIf("toolchainAvailable")
class WRK12PipelineE2EIT {

  static final String TOOLCHAIN = "emitkit/toolchain:test";
  private static final String GITHUB_TOKEN = "gho_e2e_token";
  private static final String VAULT_ROOT = "dev-root";
  private static final String S3_SECRET = "emitkit-secret";
  private static final String BUCKET = "emitkit-artifacts";
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final GitHubMock GITHUB = new GitHubMock(GITHUB_TOKEN);
  private static Path secretIdFile;

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
    GenerationRun run = runToEnd("petstore");
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
    try (InputStream bytes = storage.get(artifact.getStorageKey())) {
      assertThat(bytes.readAllBytes()).hasSize((int) artifact.getSizeBytes());
    }

    assertThat(run.getSdkVersion()).isNotBlank();
    assertThat(sdkVersions.findByProjectIdOrderByCreatedAtDesc(run.getProjectId()))
        .extracting(SdkVersion::getVersion)
        .containsExactly(run.getSdkVersion());
  }

  /**
   * Seeds a user with an encrypted GitHub token, an organization, and a project whose repo holds
   * {@code e2e/<specName>.yaml} at the spec path, with a config for every output kind. Enqueues a
   * run and waits for it to reach a terminal status.
   */
  GenerationRun runToEnd(String specName) throws IOException {
    long n = SEQUENCE.incrementAndGet();
    User creator =
        users.saveAndFlush(
            new User(
                null, 9000 + n, "e2e" + n, "E2E " + n, "e2e" + n + "@example.com", "https://a"));
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
    Organization org =
        orgs.saveAndFlush(
            new Organization(
                null, "github", "org-" + n, "e2e" + n, "E2E " + n, false, creator.getId()));
    String repo = "acme/petstore-" + n;
    String spec = new ClassPathResource("e2e/" + specName + ".yaml").getContentAsString(UTF_8);
    GITHUB.createRepo(repo, "main", Map.of("openapi.yaml", spec));
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
    var config =
        new ProjectConfig(
            null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId());
    config.setOutputs(List.of(OutputKind.SDK, OutputKind.CLI, OutputKind.MCP, OutputKind.DOCS));
    config.setSdkLanguages(List.of("typescript", "python", "java"));
    config.setNpmScope("@acme");
    config.setPypiName("acme-petstore");
    config.setMavenGroupId("dev.acme");
    config.setMavenArtifactId("petstore");
    config = configs.saveAndFlush(config);
    String runId =
        runs.saveAndFlush(
                new GenerationRun(
                    null, project.getId(), config.getId(), RunTrigger.MANUAL, false, Instant.now()))
            .getId();

    rabbit.convertAndSend(
        Queues.JOBS_EXCHANGE, Queues.JOBS_ROUTING_KEY, new GenerationJob(runId, 0));
    await()
        .atMost(Duration.ofMinutes(5))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> runs.findById(runId).orElseThrow().getStatus().isTerminal());
    return runs.findById(runId).orElseThrow();
  }
}
