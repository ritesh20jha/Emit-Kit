package dev.emitkit.vcs.github;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.vcs.error.VcsRateLimitException;
import dev.emitkit.vcs.error.VcsServerException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.HttpException;

/** Translates failures of the GitHub client into the vcs error types. */
final class GitHubErrors {

  /** Used when a rate-limit response carries no usable reset time. */
  private static final Duration FALLBACK_WAIT = Duration.ofSeconds(60);

  private GitHubErrors() {}

  /**
   * Maps a failure: 429, or 403 with Remaining 0 or Retry-After, is a rate limit; other 401 and 403
   * are auth; 409 and 422 are conflicts; 5xx and no-status are transient.
   */
  static RuntimeException map(Exception e) {
    if (e instanceof GHException && e.getCause() instanceof Exception cause) {
      // Paged listings wrap the HTTP failure that stopped the page fetch.
      return map(cause);
    }
    int status = statusOf(e);
    Map<String, List<String>> headers = headersOf(e);
    String detail = "GitHub " + status + ": " + e.getMessage();
    boolean exhausted = "0".equals(header(headers, "X-RateLimit-Remaining"));
    if (status == 429 || (status == 403 && (exhausted || header(headers, "Retry-After") != null))) {
      return new VcsRateLimitException(detail, resetAt(headers));
    }
    return switch (status) {
      case 401, 403 -> new VcsAuthException(detail);
      case 404 -> new VcsNotFoundException(detail);
      case 409, 422 -> new VcsConflictException(detail);
      case -1 -> new VcsServerException(detail, e);
      default ->
          status >= 500
              ? new VcsServerException(detail, e)
              : new AppException(ErrorCode.INTERNAL, detail, Map.of(), e);
    };
  }

  /** The HTTP status of a failure, or -1 when the failure carries none. */
  private static int statusOf(Exception e) {
    if (e instanceof HttpException http) {
      return http.getResponseCode();
    }
    return e instanceof GHFileNotFoundException ? 404 : -1;
  }

  private static Map<String, List<String>> headersOf(Exception e) {
    try {
      var headers =
          e instanceof GHIOException io
              ? io.getResponseHeaderFields()
              : e instanceof GHFileNotFoundException nf ? nf.getResponseHeaderFields() : null;
      return headers == null ? Map.of() : headers;
    } catch (NullPointerException noHeaders) {
      // kohsuke dereferences its header map, which is null when no response was recorded.
      return Map.of();
    }
  }

  /** The first value of a header, matched case-insensitively, or null. */
  private static String header(Map<String, List<String>> headers, String name) {
    return headers.entrySet().stream()
        .filter(e -> name.equalsIgnoreCase(e.getKey()) && e.getValue() != null)
        .flatMap(e -> e.getValue().stream())
        .findFirst()
        .orElse(null);
  }

  /** Retry-After wins when present, then the reset epoch, then a default wait. */
  private static Instant resetAt(Map<String, List<String>> headers) {
    String retryAfter = header(headers, "Retry-After");
    String reset = header(headers, "X-RateLimit-Reset");
    try {
      if (retryAfter != null) {
        return Instant.now().plusSeconds(Long.parseLong(retryAfter.trim()));
      }
      if (reset != null) {
        return Instant.ofEpochSecond(Long.parseLong(reset.trim()));
      }
    } catch (NumberFormatException malformed) {
      // A malformed header gives no reset time, so the default wait applies.
    }
    return Instant.now().plus(FALLBACK_WAIT);
  }
}
