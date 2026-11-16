package dev.emitkit.api.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.common.secret.SecretBackendException;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HOOK-01: the GitHub webhook endpoint verifies each delivery against the projects connected to its
 * repository, de-duplicates redeliveries and answers 503 when the secrets cannot be read. Runs
 * against Postgres and Redis; the dispatcher and the cipher's failures are test doubles.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class HOOK01WebhookIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class TestDoubles {

    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }

    @Bean
    @Primary
    RecordingDispatcher recordingDispatcher() {
      return new RecordingDispatcher();
    }
  }

  /** Records the projects it is asked to handle, and answers as the HOOK-02 stub does. */
  static class RecordingDispatcher extends WebhookDispatcher {

    private final List<Project> calls = new ArrayList<>();

    @Override
    public WebhookOutcome handle(Project project, WebhookEvent event) {
      calls.add(project);
      return super.handle(project, event);
    }

    List<Project> calls() {
      return List.copyOf(calls);
    }

    void clear() {
      calls.clear();
    }
  }

  private static final String PATH = "/webhooks/github";
  private static final String FIRST_SECRET = "first-webhook-secret";
  private static final String SECOND_SECRET = "second-webhook-secret";
  private static final String WRONG_SECRET = "not-the-webhook-secret";

  /** One GitHub delivery: its event and id, the raw JSON body, and the secret it is signed with. */
  private record Delivery(String event, String deliveryId, String body, String signingSecret) {}

  /** The status and body the endpoint answered with. */
  private record Response(int status, String body) {}

  @Autowired private MockMvc mvc;
  @Autowired private RecordingDispatcher dispatcher;
  @MockitoSpyBean private SecretCipher cipher;
  @Autowired private ProjectRepository projects;
  @Autowired private OrganizationRepository organizations;
  @Autowired private UserRepository users;

  /** A repository name of its own per test, so projects from other tests never match. */
  private String repo;

  private String lastBody;

  @BeforeEach
  void newRepository() {
    repo = "acme/widgets-" + UUID.randomUUID();
    dispatcher.clear();
  }

  @Test
  void matchesOneOfTwoCandidateProjects() throws Exception {
    project(FIRST_SECRET);
    Project second = project(SECOND_SECRET);

    Response response = deliver(delivery("push", SECOND_SECRET));

    assertThat(response.status()).isEqualTo(200);
    assertThat(dispatcher.calls()).extracting(Project::getId).containsExactly(second.getId());
  }

  @Test
  void badSignature_401() throws Exception {
    project(FIRST_SECRET);

    Response response = deliver(delivery("push", WRONG_SECRET));

    assertThat(response.status()).isEqualTo(401);
    assertThat(response.body()).contains("UNAUTHENTICATED");
    assertThat(dispatcher.calls()).isEmpty();
  }

  @Test
  void duplicateDelivery_isIgnored() throws Exception {
    project(FIRST_SECRET);
    Delivery d1 = delivery("push", FIRST_SECRET);

    deliver(d1);
    deliver(d1);

    assertThat(dispatcher.calls()).hasSize(1);
    assertThat(lastBody()).contains("duplicate");
  }

  @Test
  void vaultDown_503() throws Exception {
    project(FIRST_SECRET);
    Delivery d1 = delivery("push", FIRST_SECRET);
    failCipherWith(new SecretBackendException("sealed", null));

    Response response = deliver(d1);

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.body()).contains("SECRETS_UNAVAILABLE");
    assertThat(dispatcher.calls()).isEmpty();
  }

  @Test
  void noCsrfOrSessionNeeded() throws Exception {
    project(FIRST_SECRET);

    send(delivery("push", FIRST_SECRET))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("SESSION"));
  }

  @Test
  void ping_pong() throws Exception {
    project(FIRST_SECRET);

    Response response = deliver(delivery("ping", FIRST_SECRET));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"pong\"}");
    assertThat(dispatcher.calls()).isEmpty();
  }

  /**
   * Connects {@link #repo} in a new organization, with its webhook secret encrypted as the app
   * does.
   */
  private Project project(String secret) {
    String slug = UUID.randomUUID().toString();
    long githubId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    Organization org =
        organizations.save(new Organization(null, null, slug, slug, slug, false, null));
    User creator =
        users.save(
            new User(
                null,
                githubId,
                "user-" + slug,
                null,
                null,
                "https://avatars.example.com/u/" + githubId));
    Project project =
        projects.save(
            new Project(
                null,
                org.getId(),
                repo,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    project.setWebhookSecretEnc(
        cipher.encrypt(
            secret, new SecretContext("projects", "webhook_secret_enc", project.getId())));
    return projects.save(project);
  }

  /** A delivery for {@link #repo}, signed with {@code secret}. */
  private Delivery delivery(String event, String secret) {
    String body = "{\"repository\":{\"full_name\":\"" + repo + "\"}}";
    return new Delivery(event, UUID.randomUUID().toString(), body, secret);
  }

  /** Sends a delivery as GitHub would, with no CSRF token and no session. */
  private ResultActions send(Delivery delivery) throws Exception {
    byte[] body = delivery.body().getBytes(StandardCharsets.UTF_8);
    return mvc.perform(
        post(PATH)
            .header("X-GitHub-Event", delivery.event())
            .header("X-GitHub-Delivery", delivery.deliveryId())
            .header("X-Hub-Signature-256", sign(delivery.signingSecret(), body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private Response deliver(Delivery delivery) throws Exception {
    MvcResult result = send(delivery).andReturn();
    lastBody = result.getResponse().getContentAsString();
    return new Response(result.getResponse().getStatus(), lastBody);
  }

  private String lastBody() {
    return lastBody;
  }

  private void failCipherWith(RuntimeException failure) {
    doThrow(failure).when(cipher).decryptBatch(anyList());
  }

  /** GitHub's X-Hub-Signature-256: {@code sha256=} and the hex HMAC-SHA256 of the raw body. */
  private static String sign(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }
}
