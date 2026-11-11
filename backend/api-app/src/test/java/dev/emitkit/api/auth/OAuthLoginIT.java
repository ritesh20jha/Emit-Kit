package dev.emitkit.api.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.TokenSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub login end to end. The browser flow runs on a real server against a WireMock GitHub, so the
 * session cookie is written the way Tomcat writes it. Cookies are passed by hand because the Java
 * client's cookie store refuses Secure cookies over http.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class OAuthLoginIT {

  /**
   * Records the events published while the server handles a login. ApplicationEvents cannot see
   * them: the server publishes on its request thread, not the test thread.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class LoginEvents {
    final List<UserLoggedInEvent> published = new CopyOnWriteArrayList<>();

    @EventListener
    void record(UserLoggedInEvent event) {
      published.add(event);
    }
  }

  private static final String SCOPES = "repo,read:org,workflow,user:email,write:repo_hook";
  private static final WireMockServer GITHUB =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    GITHUB.start();
  }

  @AfterAll
  static void stopGitHub() {
    GITHUB.stop();
  }

  @DynamicPropertySource
  static void gitHubEndpoints(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.client.provider.github.authorization-uri",
        () -> GITHUB.baseUrl() + "/login/oauth/authorize");
    registry.add(
        "spring.security.oauth2.client.provider.github.token-uri",
        () -> GITHUB.baseUrl() + "/login/oauth/access_token");
    registry.add(
        "spring.security.oauth2.client.provider.github.user-info-uri",
        () -> GITHUB.baseUrl() + "/user");
  }

  @LocalServerPort private int port;
  @Autowired private TokenSource tokenSource;
  @Autowired private UserRepository users;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private StringRedisTemplate redis;
  @Autowired private LoginEvents loginEvents;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void resetData() {
    GITHUB.resetAll();
    loginEvents.published.clear();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  @Test
  void firstLogin_createsUserAndEncryptedToken() throws Exception {
    loginAs(42, "octo", "gho_token1");

    var acc = oauth.findByProviderAndProviderAccountId("github", "42").orElseThrow();
    assertThat(acc.getAccessTokenEnc()).startsWith("local:v1:").doesNotContain("gho_token1");
    assertThat(acc.getScopes()).contains("repo").contains("user:email");
    assertThat(tokenSource.accessTokenFor(acc.getUserId())).isEqualTo("gho_token1");
  }

  @Test
  void secondLogin_updatesToken() throws Exception {
    loginAs(42, "octo", "t1");
    loginAs(42, "octo", "t2");

    String userId = users.findByGithubId(42).orElseThrow().getId();
    assertThat(tokenSource.accessTokenFor(userId)).isEqualTo("t2");
    assertThat(users.count()).isEqualTo(1);
  }

  @Test
  void loginPublishesUserLoggedInEvent() throws Exception {
    loginAs(42, "octo", "gho_token1");

    String userId = users.findByGithubId(42).orElseThrow().getId();
    assertThat(loginEvents.published).extracting(UserLoggedInEvent::userId).containsExactly(userId);
  }

  @Test
  void me_returnsUserAndOrgs() throws Exception {
    String session = loginAs(42, "octo", "gho_token1");
    String userId = users.findByGithubId(42).orElseThrow().getId();
    Organization acme =
        organizations.save(new Organization(null, "github", "7", "acme", "Acme", false, userId));
    orgMembers.save(new OrgMember(acme.getId(), userId, Role.ADMIN, RoleSource.SYNC));

    HttpResponse<String> me = httpGet("/api/v1/me", cookies(session));

    assertThat(me.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(me.body());
    assertThat(body.path("id").asString()).isEqualTo(userId);
    assertThat(body.path("login").asString()).isEqualTo("octo");
    assertThat(body.path("name").asString()).isEqualTo("Octo Cat");
    assertThat(body.path("avatarUrl").asString()).isEqualTo("https://avatars.example.com/u/42");
    JsonNode orgs = body.path("orgs");
    assertThat(orgs).hasSize(1);
    assertThat(orgs.get(0).path("orgId").asString()).isEqualTo(acme.getId());
    assertThat(orgs.get(0).path("slug").asString()).isEqualTo("acme");
    assertThat(orgs.get(0).path("isPersonal").asBoolean()).isFalse();
    assertThat(orgs.get(0).path("role").asString()).isEqualTo("ADMIN");
  }

  @Test
  void sessionCookieFlags() throws Exception {
    HttpResponse<String> callback = login(42, "octo", "gho_token1");

    String setCookie = setCookieHeader(callback, "SESSION");
    assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
  }

  @Test
  void logout_invalidatesSession() throws Exception {
    String session = loginAs(42, "octo", "gho_token1");
    HttpResponse<String> before = httpGet("/api/v1/me", cookies(session));
    assertThat(before.statusCode()).isEqualTo(200);
    String xsrf = cookieValue(before, "XSRF-TOKEN");

    HttpResponse<String> logout =
        http.send(
            request("/api/v1/logout", cookies(session) + "; XSRF-TOKEN=" + xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(logout.statusCode()).isEqualTo(204);
    assertThat(httpGet("/api/v1/me", cookies(session)).statusCode()).isEqualTo(401);
  }

  @Test
  void githubTokenIsNotStoredInSession() throws Exception {
    loginAs(42, "octo", "gho_token1");

    StringBuilder sessionData = new StringBuilder();
    for (String key : redis.keys("emitkit:*")) {
      DataType type = redis.type(key);
      if (type == DataType.HASH) {
        sessionData.append(redis.opsForHash().entries(key));
      } else if (type == DataType.STRING) {
        sessionData.append(redis.opsForValue().get(key));
      }
    }
    // The principal is in the session, so the dump is not empty; the token is not.
    assertThat(sessionData).contains("octo").doesNotContain("gho_token1");
  }

  /** Runs the browser login as {@code githubId} and returns the SESSION value it issued. */
  private String loginAs(long githubId, String login, String token) throws Exception {
    HttpResponse<String> callback = login(githubId, login, token);
    assertThat(callback.statusCode()).isEqualTo(302);
    return cookieValue(callback, "SESSION");
  }

  /** Starts the authorization request and completes the callback with GitHub's stubbed answers. */
  private HttpResponse<String> login(long githubId, String login, String token) throws Exception {
    String code = "code-" + token;
    stubGitHub(githubId, login, token, code);

    HttpResponse<String> start = httpGet("/oauth2/authorization/github", null);
    assertThat(start.statusCode()).isEqualTo(302);
    String state =
        UriComponentsBuilder.fromUriString(start.headers().firstValue("Location").orElseThrow())
            .build()
            .getQueryParams()
            .getFirst("state");

    return httpGet(
        "/login/oauth2/code/github?code=" + code + "&state=" + state,
        cookies(cookieValue(start, "SESSION")));
  }

  private static void stubGitHub(long githubId, String login, String token, String code) {
    GITHUB.stubFor(
        post(urlPathEqualTo("/login/oauth/access_token"))
            .withRequestBody(containing("code=" + code))
            .willReturn(
                okJson(
                    "{\"access_token\":\""
                        + token
                        + "\",\"token_type\":\"bearer\",\"scope\":\""
                        + SCOPES
                        + "\"}")));
    GITHUB.stubFor(
        get(urlPathEqualTo("/user"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(
                okJson(
                    "{\"id\":"
                        + githubId
                        + ",\"login\":\""
                        + login
                        + "\",\"name\":\"Octo Cat\",\"email\":null,"
                        + "\"avatar_url\":\"https://avatars.example.com/u/"
                        + githubId
                        + "\"}")));
  }

  private HttpRequest.Builder request(String path, String cookieHeader) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (cookieHeader != null) {
      builder.header("Cookie", cookieHeader);
    }
    return builder;
  }

  private HttpResponse<String> httpGet(String path, String cookieHeader) throws Exception {
    return http.send(
        request(path, cookieHeader).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String cookies(String session) {
    return "SESSION=" + session;
  }

  private static String setCookieHeader(HttpResponse<?> response, String name) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(cookie -> cookie.startsWith(name + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
  }

  private static String cookieValue(HttpResponse<?> response, String name) {
    String header = setCookieHeader(response, name);
    return header.substring(name.length() + 1).split(";", 2)[0];
  }
}
