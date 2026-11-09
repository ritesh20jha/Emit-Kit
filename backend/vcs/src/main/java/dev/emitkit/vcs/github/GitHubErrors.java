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

  private static final String REMAINING = "X-RateLimit-Remaining";
  private static final String RESET = "X-RateLimit-Reset";

  /** Used when a rate-limit response carries no usable reset time. */
  private static final Duration FALLBACK_WAIT = Duration.ofSeconds(60);

  private GitHubErrors() {}

  /**
   * Maps a failure to a vcs error. A 403 or 429 whose {@code X-RateLimit-Remaining} is 0 (or any
   * 429) is a rate limit. Other 401 and 403 responses are auth failures. 404 is not found, and 409
   * and 422 are conflicts. 5xx responses and failures without a status are transient server errors.
   * Other statuses are programming errors and are not retried.
   *
   * @param e the failure thrown by the GitHub client
   * @return the mapped exception, to be thrown by the caller
   */
  static RuntimeException map(Exception e) {
    if (e instanceof GHException && e.getCause() instanceof Exception cause) {
      // Paged listings wrap the HTTP failure that stopped the page fetch.
      return map(cause);
    }
    int status = statusOf(e);
    Map<String, List<String>> headers = headersOf(e);
    String detail = "GitHub " + status + ": " + e.getMessage();
    if (status == 429 || (status == 403 && "0".equals(header(headers, REMAINING)))) {
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
      Map<String, List<String>> headers = null;
      if (e instanceof GHIOException io) {
        headers = io.getResponseHeaderFields();
      } else if (e instanceof GHFileNotFoundException notFound) {
        headers = notFound.getResponseHeaderFields();
      }
      return headers == null ? Map.of() : headers;
    } catch (NullPointerException noHeaders) {
      // The client's getter dereferences its header map, which is null when none was recorded.
      return Map.of();
    }
  }

  /** The first value of a header, matched case-insensitively, or null. */
  private static String header(Map<String, List<String>> headers, String name) {
    for (var entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())
          && entry.getValue() != null
          && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }

  private static Instant resetAt(Map<String, List<String>> headers) {
    String reset = header(headers, RESET);
    if (reset != null) {
      try {
        return Instant.ofEpochSecond(Long.parseLong(reset.trim()));
      } catch (NumberFormatException ignored) {
        // Fall through to the default wait below.
      }
    }
    return Instant.now().plus(FALLBACK_WAIT);
  }
}
