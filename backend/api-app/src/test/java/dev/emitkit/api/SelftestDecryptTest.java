package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {"management.server.port=0", "emitkit.vault.cipher=local"})
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class SelftestDecryptTest {

  private static final String PATH = "/internal/selftest/decrypt";
  private static final String PLAINTEXT = "whsec_selftest_plaintext";
  private static final String USER_ID = "01JSELFTESTUSER0000000001";
  private static final String ORG_ID = "01JSELFTESTORG00000000001";

  @LocalManagementPort private int managementPort;
  @LocalServerPort private int serverPort;
  @Autowired private SecretCipher cipher;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void seedOwner() {
    jdbc.sql(
            "insert into users (id, github_id, login, avatar_url, created_at, updated_at)"
                + " values (:id, 9001, 'selftest', 'https://example.test/a.png', now(), now())"
                + " on conflict do nothing")
        .param("id", USER_ID)
        .update();
    jdbc.sql(
            "insert into organizations (id, vcs_org_id, slug, name, is_personal, owner_user_id,"
                + " created_at) values (:id, '9001', 'selftest', 'Selftest', false, :owner, now())"
                + " on conflict do nothing")
        .param("id", ORG_ID)
        .param("owner", USER_ID)
        .update();
  }

  @Test
  void knownProjectWithValidCiphertext_isOkOnManagementPort() throws Exception {
    String projectId = "01JSELFTESTPROJ000000001";
    insertProject(projectId, cipher.encrypt(PLAINTEXT, context(projectId)));

    HttpResponse<String> response = get(managementPort, PATH + "?projectId=" + projectId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("ok");
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
  }

  @Test
  void unknownProject_isFail() throws Exception {
    HttpResponse<String> response =
        get(managementPort, PATH + "?projectId=01JSELFTESTNOPE0000000001");

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEqualTo("fail");
  }

  @Test
  void nullCiphertextColumn_isFail() throws Exception {
    String projectId = "01JSELFTESTPROJ000000002";
    insertProject(projectId, null);

    HttpResponse<String> response = get(managementPort, PATH + "?projectId=" + projectId);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEqualTo("fail");
  }

  @Test
  void ciphertextFromAnotherRow_isFailAndLeaksNothing() throws Exception {
    String projectId = "01JSELFTESTPROJ000000003";
    String otherId = "01JSELFTESTPROJ000000099";
    insertProject(projectId, cipher.encrypt(PLAINTEXT, context(otherId)));

    HttpResponse<String> response = get(managementPort, PATH + "?projectId=" + projectId);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).isEqualTo("fail").doesNotContain(PLAINTEXT);
  }

  @Test
  void samePathOnMainPort_isNotServed() throws Exception {
    String projectId = "01JSELFTESTPROJ000000004";
    insertProject(projectId, cipher.encrypt(PLAINTEXT, context(projectId)));

    HttpResponse<String> response = get(serverPort, PATH + "?projectId=" + projectId);

    assertThat(response.statusCode()).isNotEqualTo(200);
    assertThat(response.body()).doesNotContain(PLAINTEXT);
  }

  private void insertProject(String projectId, String ciphertext) {
    jdbc.sql(
            "insert into projects (id, org_id, repo_full_name, spec_path, default_branch,"
                + " output_mode, webhook_secret_enc, created_by, created_at, updated_at)"
                + " values (:id, :org, :repo, 'emitkit.yml', 'main', 'APPEND', :enc, :owner,"
                + " now(), now())")
        .param("id", projectId)
        .param("org", ORG_ID)
        .param("repo", "selftest/" + projectId)
        .param("enc", ciphertext)
        .param("owner", USER_ID)
        .update();
  }

  private static SecretContext context(String projectId) {
    return new SecretContext("projects", "webhook_secret_enc", projectId);
  }

  private HttpResponse<String> get(int port, String pathAndQuery) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
