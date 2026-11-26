package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static dev.emitkit.vcs.ratelimit.RateLimitPolicy.BACKGROUND;
import static dev.emitkit.vcs.ratelimit.RateLimitPolicy.INTERACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.ratelimit.InMemoryRateLimitStore;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import dev.emitkit.vcs.ratelimit.RateLimitStore;
import dev.emitkit.vcs.ratelimit.Sleeper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@WireMockTest
class GitHubVcsProviderTest {

  private static final Instant RESET_AT = Instant.ofEpochSecond(1900000000L);

  @Test
  void rateLimitedOverloads_requireAStore() {
    var provider =
        new GitHubVcsProvider(
            new GitHubProperties("https://api.github.com", null, 256), token -> "t", null);

    assertThatThrownBy(() -> provider.forUser("token", "u1", RateLimitPolicy.BACKGROUND))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RateLimitStore");
    assertThatThrownBy(() -> provider.forProject("u1", RateLimitPolicy.INTERACTIVE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RateLimitStore");
  }

  @Test
  void rateLimitedCall_recordsTheUsersQuota_andStillNotifiesTheListener(WireMockRuntimeInfo wm) {
    var store = new InMemoryRateLimitStore();
    var seen = new ArrayList<RateLimit>();
    stubRepos();

    List<VcsRepo> repos = provider(wm, store, seen).forUser("token", "u1", BACKGROUND).listRepos();

    assertThat(repos).hasSize(2);
    assertThat(store.get("u1")).contains(new RateLimit(0, 4998, RESET_AT));
    assertThat(seen).containsExactly(new RateLimit(0, 4998, RESET_AT));
  }

  @Test
  void forProject_recordsUnderTheCreator(WireMockRuntimeInfo wm) {
    var store = new InMemoryRateLimitStore();
    stubRepos();

    provider(wm, store, null).forProject("creator", INTERACTIVE).listRepos();

    assertThat(store.get("creator")).contains(new RateLimit(0, 4998, RESET_AT));
    assertThat(store.get("u1")).isEmpty();
  }

  @Test
  void failingStore_doesNotFailTheCall(WireMockRuntimeInfo wm) {
    RateLimitStore failing =
        new RateLimitStore() {
          @Override
          public void record(String userId, int remaining, Instant resetAt) {
            throw new IllegalStateException("redis down");
          }

          @Override
          public Optional<RateLimit> get(String userId) {
            return Optional.empty();
          }
        };
    stubRepos();

    assertThat(provider(wm, failing, null).forUser("token", "u1", BACKGROUND).listRepos())
        .hasSize(2);
  }

  /** The user lookup and a repository list with two pushable repos and a quota reading. */
  private static void stubRepos() {
    stubFor(get(urlPathEqualTo("/user")).willReturn(okJson(body("user.json"))));
    stubFor(
        get(urlPathEqualTo("/user/repos"))
            .willReturn(
                okJson(body("repos.json"))
                    .withHeader("X-RateLimit-Remaining", "4998")
                    .withHeader("X-RateLimit-Reset", "1900000000")));
  }

  private static GitHubVcsProvider provider(
      WireMockRuntimeInfo wm, RateLimitStore store, List<RateLimit> seen) {
    var props = new GitHubProperties(wm.getHttpBaseUrl(), null, 256);
    RateLimitListener listener = null;
    if (seen != null) {
      listener = (remaining, reset) -> seen.add(new RateLimit(0, remaining, reset));
    }
    return new GitHubVcsProvider(
        props, token -> "t", listener, store, Clock.systemUTC(), Sleeper.SYSTEM);
  }

  private static String body(String name) {
    try (var in = GitHubVcsProviderTest.class.getResourceAsStream("/wiremock/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
