package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class GitHubVcsClientFilesTest {

  private static final String RESET = "1900000000";
  private static final Instant RESET_AT = Instant.ofEpochSecond(1900000000L);

  /** One rate-limit notification received by the listener. */
  private record Seen(int remaining, Instant reset) {}

  private VcsClient client;

  @BeforeEach
  void setUp(WireMockRuntimeInfo wm) {
    // The client looks up the identity and the repository first.
    stubFor(get(urlPathEqualTo("/user")).willReturn(okJson(body("user.json"))));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api"))
            .willReturn(okJson("{\"full_name\":\"acme/api\",\"owner\":{\"login\":\"acme\"}}")));
    client = provider(wm, null).forUser("token");
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
    verify(
        putRequestedFor(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .withRequestBody(matchingJsonPath("$.sha", equalTo("sha1"))));
  }

  @Test
  void putFile_shaMismatch409_isConflict() {
    stubFor(
        put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(409)));

    assertThatThrownBy(() -> client.putFile("acme/api", "openapi.yaml", "v2", "stale", "m"))
        .isInstanceOf(VcsConflictException.class);
  }

  @Test
  void unauthorized_isAuthException() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(401)));

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
                    .withHeader("X-RateLimit-Reset", RESET)));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOfSatisfying(
            VcsRateLimitException.class, e -> assertThat(e.resetAt()).isEqualTo(RESET_AT));
  }

  @Test
  void serverError_isTransient() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(502)));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOf(VcsServerException.class)
        .isInstanceOf(TransientException.class);
    // A 5xx on a later page of a listing is the same failure.
    stubFor(get(urlPathEqualTo("/user/repos")).willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(() -> client.listRepos()).isInstanceOf(VcsServerException.class);
  }

  @Test
  void listOrgs_includesPersonalAccountAsOwner() {
    stubFor(
        get(urlPathEqualTo("/user/memberships/orgs")).willReturn(okJson(body("memberships.json"))));

    stubFor(get(urlPathMatching("/orgs/.*")).willReturn(aResponse().withStatus(403)));
    // Membership payloads carry no name, so no org is fetched, and a 403 on an org fetch is
    // harmless.
    assertThat(client.listOrgs())
        .containsExactly(
            new VcsOrg("7", "ritesh", "ritesh", true, VcsRole.OWNER),
            new VcsOrg("1", "acme", "acme", false, VcsRole.OWNER),
            new VcsOrg("2", "beta", "beta", false, VcsRole.MEMBER),
            new VcsOrg("3", "gamma", "gamma", false, VcsRole.MEMBER));
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
  void cache_keepsTokensApart_andNeverStoresTheToken(WireMockRuntimeInfo wm, @TempDir Path cacheDir)
      throws IOException {
    var props = new GitHubProperties(wm.getHttpBaseUrl(), cacheDir, 16);
    var provider = new GitHubVcsProvider(props, t -> "t", null);
    stubCachedContents("gho_alice_secret", "contents.json");
    stubCachedContents("gho_bob_secret", "contents-bob.json");
    // GitHub's ETag does not depend on the token, so a 304 would hand Alice's body to Bob.
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .withHeader("If-None-Match", equalTo("\"v1\""))
            .atPriority(1)
            .willReturn(aResponse().withStatus(304)));

    String alice =
        provider.forUser("gho_alice_secret").getFile("acme/api", "openapi.yaml", "main").content();
    String bob =
        provider.forUser("gho_bob_secret").getFile("acme/api", "openapi.yaml", "main").content();
    assertThat(alice).isEqualTo("openapi: 3.1.0");
    assertThat(bob).isEqualTo("openapi: bob");

    try (Stream<Path> files = Files.walk(cacheDir)) {
      List<Path> cached = files.filter(Files::isRegularFile).toList();
      assertThat(cached).isNotEmpty();
      for (Path file : cached) {
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
            .doesNotContain("gho_alice_secret", "gho_bob_secret");
      }
    }
  }

  /**
   * Serves the fixture to one token only, with a Vary on Authorization and a fresh-for-a-minute
   * TTL.
   */
  private static void stubCachedContents(String token, String fixture) {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .withHeader("Authorization", equalTo("token " + token))
            .willReturn(
                okJson(body(fixture))
                    .withHeader("Vary", "Accept, Authorization")
                    .withHeader("ETag", "\"v1\"")
                    .withHeader("Cache-Control", "private, max-age=60")));
  }

  @Test
  void listRepos_capsAtThreeHundred() {
    String repos =
        IntStream.range(0, 305)
            .mapToObj(i ->
                "{\"full_name\":\"acme/r"
                    + i
                    + "\",\"owner\":{\"login\":\"acme\"},\"permissions\":{\"push\":true}}")
            .collect(joining(",", "[", "]"));
    stubFor(get(urlPathEqualTo("/user/repos")).willReturn(okJson(repos)));

    assertThat(client.listRepos()).hasSize(300);
  }

  @Test
  void secondaryRateLimit_403WithRetryAfter_isRateLimitWithThatDelay() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("Retry-After", "120")
                    .withHeader("X-RateLimit-Remaining", "4000")));

    Instant before = Instant.now();
    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOfSatisfying(
            VcsRateLimitException.class,
            e ->
                assertThat(e.resetAt())
                    .isBetween(before.plusSeconds(120), Instant.now().plusSeconds(120)));
  }

  @Test
  void plain429_isRateLimitException() {
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(aResponse().withStatus(429)));

    assertThatThrownBy(() -> client.getFile("acme/api", "openapi.yaml", "main"))
        .isInstanceOf(VcsRateLimitException.class);
  }

  @Test
  void createRepo_postsToOrgThenToUserRepos() {
    stubFor(get(urlPathEqualTo("/orgs/acme")).willReturn(okJson("{\"login\":\"acme\",\"id\":1}")));
    stubFor(post(urlPathEqualTo("/orgs/acme/repos")).willReturn(okJson(body("repo.json"))));
    stubFor(post(urlPathEqualTo("/user/repos")).willReturn(okJson(body("repo.json"))));

    assertThat(client.createRepo("acme", "web", true))
        .isEqualTo(
            new VcsRepo("acme/web", "main", true, Instant.parse("2026-11-09T00:00:00Z"), "acme"));
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
    // A listener that throws must neither fail the call nor change how its error is mapped.
    client =
        provider(
                wm,
                (remaining, reset) -> {
                  seen.add(new Seen(remaining, reset));
                  throw new IllegalStateException("redis down");
                })
            .forUser("t");
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
                    .withHeader("X-RateLimit-Reset", RESET)));

    assertThat(client.getFile("acme/api", "openapi.yaml", "main").content())
        .isEqualTo("openapi: 3.1.0");
    assertThatThrownBy(() -> client.getFile("acme/api", "nope.yaml", "main"))
        .isInstanceOf(VcsNotFoundException.class);
    assertThat(seen).containsExactly(new Seen(4998, RESET_AT), new Seen(4997, RESET_AT));
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
