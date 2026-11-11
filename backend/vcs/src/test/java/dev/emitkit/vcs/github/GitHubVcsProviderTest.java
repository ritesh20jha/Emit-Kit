package dev.emitkit.vcs.github;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import org.junit.jupiter.api.Test;

class GitHubVcsProviderTest {

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
}
