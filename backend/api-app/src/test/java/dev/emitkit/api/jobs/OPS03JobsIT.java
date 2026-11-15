package dev.emitkit.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.common.secret.VaultTransitCipher;
import dev.emitkit.domain.storage.ArtifactStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
      "management.server.port=0",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@Import({RedisTestConfig.class, PostgresTestConfig.class, OPS03JobsIT.VaultCipherConfig.class})
@Testcontainers
class OPS03JobsIT {

  private static final String KEY = "emitkit-secrets";
  private static final String PLAINTEXT = "whsec_ops03_plaintext";
  private static final String OWNER = "01OPS03OWNER0000000000001";
  private static final String ORG = "01OPS03ORG00000000000001";

  @Container static VaultContainer<?> vault = newVault();

  @LocalManagementPort private int managementPort;
  @Autowired private RetentionJob retention;
  @Autowired private RewrapJob rewrap;
  @Autowired private SecretCipher cipher;
  @Autowired private JdbcClient jdbc;
  @MockitoBean private ArtifactStorage storage;

  @BeforeEach
  void seedOwnerAndOrg() {
    jdbc.sql(
            "insert into users (id, github_id, login, avatar_url, created_at, updated_at)"
                + " values (:id, 9101, 'ops03', 'https://example.test/a.png', now(), now())"
                + " on conflict do nothing")
        .param("id", OWNER)
        .update();
    jdbc.sql(
            "insert into organizations (id, vcs_org_id, slug, name, is_personal, owner_user_id,"
                + " created_at) values (:id, '9101', 'ops03', 'OPS03', false, :owner, now())"
                + " on conflict do nothing")
        .param("id", ORG)
        .param("owner", OWNER)
        .update();
  }

  @Test
  void retention_deletesOnlyOldLogsAndArtifacts() {
    String projectId = insertProject(newId(), null);
    String configId = insertConfig(newId(), projectId, null);
    String oldRun = insertRun(newId(), projectId, configId, daysAgo(200));
    String newRun = insertRun(newId(), projectId, configId, daysAgo(1));
    insertLog(oldRun, 1, daysAgo(91));
    insertLog(oldRun, 2, daysAgo(1));
    insertArtifact(newId(), oldRun, "artifacts/ops03/old.zip", daysAgo(31));
    insertArtifact(newId(), newRun, "artifacts/ops03/new.zip", daysAgo(29));

    retention.run();

    assertThat(count("select count(*) from generation_run_logs where run_id = :id", oldRun))
        .isOne();
    verify(storage).delete("artifacts/ops03/old.zip");
    verify(storage, never()).delete("artifacts/ops03/new.zip");
    assertThat(
            count(
                "select count(*) from run_artifacts where storage_key = :id",
                "artifacts/ops03/old.zip"))
        .isZero();
    assertThat(
            count(
                "select count(*) from run_artifacts where storage_key = :id",
                "artifacts/ops03/new.zip"))
        .isOne();
  }

  @Test
  void retention_keepsRunRows() {
    String projectId = insertProject(newId(), null);
    String configId = insertConfig(newId(), projectId, null);
    String run = insertRun(newId(), projectId, configId, daysAgo(400));
    insertLog(run, 1, daysAgo(400));
    insertArtifact(newId(), run, "artifacts/ops03/kept-run.zip", daysAgo(400));

    retention.run();

    assertThat(count("select count(*) from generation_runs where id = :id", run)).isOne();
  }

  @Test
  void rewrap_updatesVersion_plaintextUnchanged() {
    int before = cipher.latestKeyVersion();
    String projectId = newId();
    String oauthId = newId();
    String configId = newId();
    SecretContext projectCtx = new SecretContext("projects", "webhook_secret_enc", projectId);
    SecretContext oauthCtx = new SecretContext("oauth_accounts", "access_token_enc", oauthId);
    SecretContext configCtx = new SecretContext("project_configs", "ai_api_key_enc", configId);
    insertProject(projectId, cipher.encrypt(PLAINTEXT, projectCtx));
    insertOAuth(oauthId, cipher.encrypt(PLAINTEXT, oauthCtx));
    insertConfig(configId, insertProject(newId(), null), cipher.encrypt(PLAINTEXT, configCtx));
    rotateKey();

    rewrap.rewrapAll();

    String rewrapped = "vault:v" + (before + 1) + ":";
    String projectCipher =
        stored("select webhook_secret_enc from projects where id = :id", projectId);
    String oauthCipher =
        stored("select access_token_enc from oauth_accounts where id = :id", oauthId);
    String configCipher =
        stored("select ai_api_key_enc from project_configs where id = :id", configId);
    assertThat(projectCipher).startsWith(rewrapped);
    assertThat(oauthCipher).startsWith(rewrapped);
    assertThat(configCipher).startsWith(rewrapped);
    assertThat(cipher.decrypt(projectCipher, projectCtx)).isEqualTo(PLAINTEXT);
    assertThat(cipher.decrypt(oauthCipher, oauthCtx)).isEqualTo(PLAINTEXT);
    assertThat(cipher.decrypt(configCipher, configCtx)).isEqualTo(PLAINTEXT);
  }

  @Test
  void rewrap_reportsZeroRemaining() throws Exception {
    String projectId = newId();
    insertProject(
        projectId,
        cipher.encrypt(PLAINTEXT, new SecretContext("projects", "webhook_secret_enc", projectId)));
    rotateKey();

    HttpResponse<String> response = post(managementPort, "/internal/rewrap");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<Map<String, Integer>>read(response.body(), "$"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("access_token_enc", 0, "webhook_secret_enc", 0, "ai_api_key_enc", 0));
  }

  @Test
  void lockPreventsConcurrentRuns() throws Exception {
    String projectId = insertProject(newId(), null);
    String configId = insertConfig(newId(), projectId, null);
    String run = insertRun(newId(), projectId, configId, daysAgo(400));
    insertArtifact(newId(), run, "artifacts/ops03/locked.zip", daysAgo(400));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger deletes = new AtomicInteger();
    doAnswer(
            invocation -> {
              deletes.incrementAndGet();
              entered.countDown();
              release.await();
              return null;
            })
        .when(storage)
        .delete(anyString());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(retention::run);
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

      pool.submit(retention::run).get(10, TimeUnit.SECONDS);

      assertThat(deletes).hasValue(1);
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private String insertProject(String id, String webhookCiphertext) {
    jdbc.sql(
            "insert into projects (id, org_id, repo_full_name, spec_path, default_branch,"
                + " output_mode, webhook_secret_enc, created_by, created_at, updated_at)"
                + " values (:id, :org, :repo, 'emitkit.yml', 'main', 'APPEND', :enc, :owner,"
                + " now() - interval '400 days', now())")
        .param("id", id)
        .param("org", ORG)
        .param("repo", "ops03/" + id)
        .param("enc", webhookCiphertext)
        .param("owner", OWNER)
        .update();
    return id;
  }

  private String insertConfig(String id, String projectId, String aiCiphertext) {
    jdbc.sql(
            "insert into project_configs (id, project_id, version, version_strategy, ai_api_key_enc,"
                + " created_by, created_at) values (:id, :project, 1, 'EMITKIT_MANAGED', :enc,"
                + " :owner, now() - interval '400 days')")
        .param("id", id)
        .param("project", projectId)
        .param("enc", aiCiphertext)
        .param("owner", OWNER)
        .update();
    return id;
  }

  private String insertRun(String id, String projectId, String configId, Instant createdAt) {
    jdbc.sql(
            "insert into generation_runs (id, project_id, config_id, trigger, status, created_at)"
                + " values (:id, :project, :config, 'MANUAL', 'SUCCESS', :created)")
        .param("id", id)
        .param("project", projectId)
        .param("config", configId)
        .param("created", Timestamp.from(createdAt))
        .update();
    return id;
  }

  private void insertLog(String runId, int seq, Instant ts) {
    jdbc.sql(
            "insert into generation_run_logs (run_id, seq, ts, level, message)"
                + " values (:run, :seq, :ts, 'INFO', 'ops03')")
        .param("run", runId)
        .param("seq", seq)
        .param("ts", Timestamp.from(ts))
        .update();
  }

  private void insertArtifact(String id, String runId, String storageKey, Instant createdAt) {
    jdbc.sql(
            "insert into run_artifacts (id, run_id, storage_key, size_bytes, file_count, manifest,"
                + " created_at) values (:id, :run, :key, 1, 1, '[]'::jsonb, :created)")
        .param("id", id)
        .param("run", runId)
        .param("key", storageKey)
        .param("created", Timestamp.from(createdAt))
        .update();
  }

  private void insertOAuth(String id, String accessCiphertext) {
    jdbc.sql(
            "insert into oauth_accounts (id, user_id, provider, provider_account_id,"
                + " access_token_enc, scopes, updated_at)"
                + " values (:id, :owner, 'github', :account, :enc, 'repo', now())")
        .param("id", id)
        .param("owner", OWNER)
        .param("account", id)
        .param("enc", accessCiphertext)
        .update();
  }

  private long count(String sql, String param) {
    return jdbc.sql(sql).param("id", param).query(Long.class).single();
  }

  private String stored(String sql, String id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }

  private void rotateKey() {
    vaultTemplate().opsForTransit().rotate(KEY);
  }

  private HttpResponse<String> post(int port, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static Instant daysAgo(long days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
  }

  private static VaultTemplate vaultTemplate() {
    return new VaultTemplate(
        VaultEndpoint.from(URI.create(vault.getHttpHostAddress())),
        new TokenAuthentication("root"));
  }

  private static VaultContainer<?> newVault() {
    return new VaultContainer<>("hashicorp/vault:2.2.1")
        .withVaultToken("root")
        .withInitCommand(
            "secrets enable transit",
            "write -f transit/keys/" + KEY + " type=aes256-gcm96 derived=true");
  }

  /** The job runs against a real Vault transit key, so the re-wrap is the one Vault performs. */
  @TestConfiguration(proxyBeanMethods = false)
  static class VaultCipherConfig {

    @Bean
    SecretCipher secretCipher() {
      return new VaultTransitCipher(vaultTemplate().opsForTransit(), KEY);
    }
  }
}
