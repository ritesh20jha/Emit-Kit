package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsOrg.VcsRole;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.vcs.error.VcsRateLimitException;
import dev.emitkit.vcs.error.VcsServerException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class GitHubVcsClientFilesTest {

  private static final String RESET = "1900000000";
  private static final Instant RESET_AT = Instant.ofEpochSecond(1900000000L);

  /** One rate-limit notification received by the listener. */
  private record Seen(int remaining, Instant reset) {}

  private GitHubVcsProvider provider;
  private VcsClient client;

  @BeforeEach
  void setUp(WireMockRuntimeInfo wm) {
    // The client looks up the identity and the repository before reading or writing contents.
    stubFor(get(urlPathEqualTo("/user")).willReturn(okJson(body("user.json"))));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api"))
            .willReturn(
                okJson(
                    "{\"name\":\"api\",\"full_name\":\"acme/api\",\"owner\":{\"login\":\"acme\"}}")));
    provider = provider(wm, null);
    client = provider.forUser("token");
  }

  @Test
  void getFile_decodesBase64Content() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(okJson(body("contents.json"))));

    assertThat(client.getFile("acme/api", "openapi.yaml", "main"))
        .isEqualTo(new FileContent("openapi: 3.1.0", "sha1"));
  }

  @Test
  void getFile_largeFile_fallsBackToBlobApi() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/big.bin"))
            .willReturn(okJson(body("contents-large.json"))));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/git/blobs/bigsha"))
            .willReturn(okJson(body("blob.json"))));

    assertThat(client.getFile("acme/api", "big.bin", "main"))
        .isEqualTo(new FileContent("openapi: 3.1.0", "bigsha"));
  }

  @Test
  void putFile_returnsCommitShaAndUrl() {
    stubFor(
        put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(okJson(body("commit.json"))));

    assertThat(client.putFile("acme/api", "openapi.yaml", "v2", "sha1", "m"))
        .isEqualTo(new CommitRef("c0ffee", "https://github.com/acme/api/commit/c0ffee"));
  }

  @Test
  void putFile_shaMismatch409_isConflict() {
    stubFor(
        put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"does not match\"}")));

    assertThatThrownBy(() -> client.putFile("acme/api", "openapi.yaml", "v2", "stale", "m"))
        .isInstanceOf(VcsConflictException.class);
  }

  @Test
  void unauthorized_isAuthException() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOf(VcsAuthException.class);
  }

  @Test
  void forbidden_withoutRateLimitHeaders_isAuthException() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"Forbidden\"}")));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOf(VcsAuthException.class);
  }

  @Test
  void rateLimited_isRateLimitException_withReset() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("X-RateLimit-Remaining", "0")
                    .withHeader("X-RateLimit-Reset", RESET)
                    .withBody("{\"message\":\"API rate limit exceeded\"}")));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOfSatisfying(
            VcsRateLimitException.class, e -> assertThat(e.resetAt()).isEqualTo(RESET_AT));
  }

  @Test
  void serverError_isTransient() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(502).withBody("{\"message\":\"Bad gateway\"}")));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOf(VcsServerException.class)
        .isInstanceOf(TransientException.class);
  }

  @Test
  void listOrgs_includesPersonalAccountAsOwner() {
    stubFor(
        get(urlPathEqualTo("/user/memberships/orgs")).willReturn(okJson(body("memberships.json"))));

    assertThat(client.listOrgs())
        .containsExactly(
            new VcsOrg("7", "ritesh", "Ritesh Jha", true, VcsRole.OWNER),
            new VcsOrg("1", "acme", "acme", false, VcsRole.OWNER),
            new VcsOrg("2", "beta", "beta", false, VcsRole.MEMBER));
  }

  @Test
  void listOrgs_fetchesNoOrgDetails_soForbiddenOrgsDoNotFailTheListing() {
    stubFor(
        get(urlPathEqualTo("/user/memberships/orgs")).willReturn(okJson(body("memberships.json"))));
    stubFor(get(urlPathMatching("/orgs/.*")).willReturn(aResponse().withStatus(403)));

    assertThat(client.listOrgs()).hasSize(3);
    verify(0, getRequestedFor(urlPathMatching("/orgs/.*")));
  }

  @Test
  void listRepos_pushableOnly_sortedByPushedAtDesc() {
    stubFor(get(urlPathEqualTo("/user/repos")).willReturn(okJson(body("repos.json"))));

    assertThat(client.listRepos())
        .containsExactly(
            new VcsRepo(
                "ritesh/gamma", "trunk", true, Instant.parse("2026-02-10T00:00:00Z"), "ritesh"),
            new VcsRepo(
                "acme/alpha", "main", false, Instant.parse("2026-01-10T00:00:00Z"), "acme"));
  }

  @Test
  void listRepos_serverErrorOnPage_isServerException() {
    stubFor(
        get(urlPathEqualTo("/user/repos"))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"Server Error\"}")));

    assertThatThrownBy(() -> client.listRepos()).isInstanceOf(VcsServerException.class);
  }

  @Test
  void getFile_withCache_revalidatesWithEtag(WireMockRuntimeInfo wm, @TempDir Path cacheDir) {
    client =
        new GitHubVcsProvider(
                new GitHubProperties(wm.getHttpBaseUrl(), cacheDir, 16), token -> "t", null)
            .forUser("t");
    // GitHub marks its responses fresh for a minute. The client must still revalidate them.
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                okJson(body("contents.json"))
                    .withHeader("ETag", "\"v1\"")
                    .withHeader("Cache-Control", "private, max-age=60")));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .withHeader("If-None-Match", equalTo("\"v1\""))
            .atPriority(1)
            .willReturn(aResponse().withStatus(304).withHeader("ETag", "\"v1\"")));

    var expected = new FileContent("openapi: 3.1.0", "sha1");
    assertThat(client.getFile("acme/api", "openapi.yaml", "main")).isEqualTo(expected);
    assertThat(client.getFile("acme/api", "openapi.yaml", "main")).isEqualTo(expected);

    verify(2, getRequestedFor(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml")));
    verify(
        getRequestedFor(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .withHeader("If-None-Match", equalTo("\"v1\"")));
  }

  @Test
  void listRepos_capsAtThreeHundred() {
    String repos =
        IntStream.range(0, 305)
            .mapToObj(i ->
                "{\"name\":\"r"
                    + i
                    + "\",\"full_name\":\"acme/r"
                    + i
                    + "\",\"private\":false,\"default_branch\":\"main\",\"owner\":{\"login\":\"acme\"},"
                    + "\"pushed_at\":\"2026-01-01T00:00:00Z\",\"permissions\":{\"push\":true}}")
            .collect(joining(",", "[", "]"));
    stubFor(get(urlPathEqualTo("/user/repos")).willReturn(okJson(repos)));

    assertThat(client.listRepos()).hasSize(300);
  }

  @Test
  void createRepo_inOrg_postsToOrgRepos() {
    stubFor(get(urlPathEqualTo("/orgs/acme")).willReturn(okJson("{\"login\":\"acme\",\"id\":1}")));
    stubFor(
        post(urlPathEqualTo("/orgs/acme/repos"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body("repo.json"))));

    assertThat(client.createRepo("acme", "web", true))
        .isEqualTo(
            new VcsRepo("acme/web", "main", true, Instant.parse("2026-11-09T00:00:00Z"), "acme"));
  }

  @Test
  void createRepo_personalAccount_postsToUserRepos() {
    stubFor(get(urlPathEqualTo("/user")).willReturn(okJson(body("user.json"))));
    stubFor(
        post(urlPathEqualTo("/user/repos"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body("repo.json"))));

    client.createRepo("ritesh", "web", true);

    verify(postRequestedFor(urlPathEqualTo("/user/repos")));
  }

  @Test
  void rateLimit_readsCoreQuota() {
    stubFor(get(urlPathEqualTo("/rate_limit")).willReturn(okJson(body("rate-limit.json"))));

    assertThat(client.rateLimit()).isEqualTo(new RateLimit(5000, 4999, RESET_AT));
  }

  @Test
  void rateLimitHeadersReachListener(WireMockRuntimeInfo wm) {
    var seen = new ArrayList<Seen>();
    client = provider(wm, (remaining, reset) -> seen.add(new Seen(remaining, reset))).forUser("t");
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                okJson(body("contents.json"))
                    .withHeader("X-RateLimit-Remaining", "4998")
                    .withHeader("X-RateLimit-Reset", RESET)));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/nope.yaml"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("X-RateLimit-Remaining", "4997")
                    .withHeader("X-RateLimit-Reset", RESET)
                    .withBody("{\"message\":\"Not Found\"}")));

    client.getFile("acme/api", "openapi.yaml", "main");
    assertThatThrownBy(() -> client.getFile("acme/api", "nope.yaml", "main"))
        .isInstanceOf(VcsNotFoundException.class);

    assertThat(seen).containsExactly(new Seen(4998, RESET_AT), new Seen(4997, RESET_AT));
  }

  @Test
  void rateLimitListenerFailure_doesNotFailTheCall(WireMockRuntimeInfo wm) {
    client =
        provider(
                wm,
                (remaining, reset) -> {
                  throw new IllegalStateException("redis down");
                })
            .forUser("t");
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                okJson(body("contents.json"))
                    .withHeader("X-RateLimit-Remaining", "4998")
                    .withHeader("X-RateLimit-Reset", RESET)));

    assertThat(client.getFile("acme/api", "openapi.yaml", "main"))
        .isEqualTo(new FileContent("openapi: 3.1.0", "sha1"));
  }

  @Test
  void outOfScopeMethods_throwUnsupportedOperation() {
    assertThatThrownBy(() -> client.createBranch("acme/api", "b", "sha"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("VCS-03");
    assertThatThrownBy(
            () -> client.registerWebhook("acme/api", "https://hook", "secret", Set.of("push")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("VCS-04");
    assertThatThrownBy(() -> provider.webhookVerifier())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("VCS-04");
  }

  private GitHubVcsProvider provider(WireMockRuntimeInfo wm, RateLimitListener listener) {
    var props = new GitHubProperties(wm.getHttpBaseUrl(), null, 256);
    return new GitHubVcsProvider(props, token -> "token", listener);
  }

  private static String body(String name) {
    try (var in = GitHubVcsClientFilesTest.class.getResourceAsStream("/wiremock/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
