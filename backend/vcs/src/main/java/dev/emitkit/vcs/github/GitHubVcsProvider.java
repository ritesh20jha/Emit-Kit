package dev.emitkit.vcs.github;

import static java.util.stream.Collectors.joining;

import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.VcsProviderId;
import dev.emitkit.vcs.WebhookVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
  private static final String TOKEN_KEY = "X-EmitKit-Token-Key";

  private final GitHubProperties props;
  private final TokenSource tokens;
  private final OkHttpClient http;

  /** Creates the provider. The listener may be null. */
  public GitHubVcsProvider(GitHubProperties props, TokenSource tokens, RateLimitListener listener) {
    this.props = Objects.requireNonNull(props, "props");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.http = httpClient(props, listener);
  }

  @Override
  public VcsProviderId id() {
    return VcsProviderId.GITHUB;
  }

  // kohsuke 1.330 has no public replacement for RateLimitChecker.NONE.
  @Override
  @SuppressWarnings("deprecation")
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
    return new GitHubWebhookVerifier();
  }

  private static OkHttpClient httpClient(GitHubProperties props, RateLimitListener listener) {
    var builder =
        new OkHttpClient.Builder()
            // Vary metadata stores request header values, so the token is replaced by its hash.
            .addInterceptor(GitHubVcsProvider::withTokenKey)
            // Network-level, so every real response (304s too) is seen but cache hits are not.
            .addNetworkInterceptor(chain -> {
              Response response = scopeVaryToToken(chain.proceed(chain.request()));
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

  /** Adds the SHA-256 of the Authorization header as {@link #TOKEN_KEY}. */
  private static Response withTokenKey(Interceptor.Chain chain) throws IOException {
    Request request = chain.request();
    String authorization = request.header("Authorization");
    if (authorization == null) {
      return chain.proceed(request);
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(authorization.getBytes(StandardCharsets.UTF_8));
      return chain.proceed(
          request.newBuilder().header(TOKEN_KEY, HexFormat.of().formatHex(digest)).build());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Names {@link #TOKEN_KEY} in Vary wherever the response named Authorization. */
  private static Response scopeVaryToToken(Response response) {
    String vary = response.header("Vary");
    if (vary == null || !vary.toLowerCase(Locale.ROOT).contains("authorization")) {
      return response;
    }
    String scoped =
        Arrays.stream(vary.split(","))
            .map(String::trim)
            .map(field -> field.equalsIgnoreCase("Authorization") ? TOKEN_KEY : field)
            .collect(joining(", "));
    return response.newBuilder().header("Vary", scoped).build();
  }

  private static void notifyRateLimit(Response response, RateLimitListener listener) {
    String remaining = response.header("X-RateLimit-Remaining");
    String reset = response.header("X-RateLimit-Reset");
    if (remaining == null || reset == null) {
      return;
    }
    try {
      listener.onHeaders(
          Integer.parseInt(remaining.trim()), Instant.ofEpochSecond(Long.parseLong(reset.trim())));
    } catch (NumberFormatException malformed) {
      // A malformed header is not a quota reading.
    } catch (RuntimeException e) {
      // The quota is advisory: a failing listener must not fail the GitHub call.
      LOG.log(System.Logger.Level.WARNING, "Rate-limit listener failed; call continues", e);
    }
  }
}
