package dev.emitkit.vcs.github;

import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.VcsProviderId;
import dev.emitkit.vcs.WebhookVerifier;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubAbuseLimitHandler;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GitHubRateLimitHandler;
import org.kohsuke.github.RateLimitChecker;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;

/**
 * The GitHub implementation of {@link VcsProvider}. Clients share one OkHttp client with a response
 * cache, and every response's rate-limit headers go to the optional {@link RateLimitListener}.
 */
public final class GitHubVcsProvider implements VcsProvider {

  private static final System.Logger LOG = System.getLogger(GitHubVcsProvider.class.getName());

  private final GitHubProperties props;
  private final TokenSource tokens;
  private final OkHttpClient http;

  /**
   * Creates the provider.
   *
   * @param props the API root and cache settings
   * @param tokens looks up the creator's token for project-scoped clients
   * @param listener receives the rate-limit headers of every response, or null to ignore them
   */
  public GitHubVcsProvider(GitHubProperties props, TokenSource tokens, RateLimitListener listener) {
    this.props = Objects.requireNonNull(props, "props");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.http = httpClient(props, listener);
  }

  @Override
  public VcsProviderId id() {
    return VcsProviderId.GITHUB;
  }

  @Override
  @SuppressWarnings(
      "deprecation") // kohsuke 1.330 has no public replacement for RateLimitChecker.NONE.
  public VcsClient forUser(String accessToken) {
    GitHub github;
    try {
      github =
          new GitHubBuilder()
              .withEndpoint(props.apiUrl())
              .withOAuthToken(accessToken)
              .withConnector(new OkHttpGitHubConnector(http))
              // Fail fast: waiting for a reset is the caller's decision, not the client's.
              .withRateLimitChecker(RateLimitChecker.NONE)
              .withRateLimitHandler(GitHubRateLimitHandler.FAIL)
              .withAbuseLimitHandler(GitHubAbuseLimitHandler.FAIL)
              .build();
    } catch (IOException e) {
      throw GitHubErrors.map(e);
    }
    return new GitHubVcsClient(github);
  }

  @Override
  public VcsClient forProject(String creatorUserId) {
    return forUser(tokens.accessTokenFor(creatorUserId));
  }

  @Override
  public WebhookVerifier webhookVerifier() {
    throw new UnsupportedOperationException("VCS-04");
  }

  private static OkHttpClient httpClient(GitHubProperties props, RateLimitListener listener) {
    var builder =
        new OkHttpClient.Builder()
            // A network interceptor sees every real response, including 304s, and not cache hits.
            // The connector sends Cache-Control: max-age=0, so cached responses are revalidated.
            .addNetworkInterceptor(chain -> {
              Response response = chain.proceed(chain.request());
              if (listener != null) {
                notifyRateLimit(response, listener);
              }
              return response;
            });
    if (props.cacheDir() != null) {
      builder.cache(new Cache(props.cacheDir().toFile(), props.cacheSizeMb() * 1024 * 1024));
    }
    return builder.build();
  }

  private static void notifyRateLimit(Response response, RateLimitListener listener) {
    String remaining = response.header("X-RateLimit-Remaining");
    String reset = response.header("X-RateLimit-Reset");
    if (remaining == null || reset == null) {
      return;
    }
    int left;
    Instant resetAt;
    try {
      left = Integer.parseInt(remaining.trim());
      resetAt = Instant.ofEpochSecond(Long.parseLong(reset.trim()));
    } catch (NumberFormatException malformed) {
      return; // A malformed header is not a quota reading.
    }
    try {
      listener.onHeaders(left, resetAt);
    } catch (RuntimeException e) {
      // The quota is advisory: a failing listener must not fail the GitHub call.
      LOG.log(System.Logger.Level.WARNING, "Rate-limit listener failed; call continues", e);
    }
  }
}
