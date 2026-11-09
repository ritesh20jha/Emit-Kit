package dev.emitkit.vcs.github;

import java.time.Instant;

/**
 * Receives the rate-limit headers of every GitHub response. VCS-05 uses this to record quota per
 * token.
 */
public interface RateLimitListener {

  /**
   * Called once per response that carries rate-limit headers.
   *
   * @param remaining the requests left in the current window
   * @param reset when the window resets
   */
  void onHeaders(int remaining, Instant reset);
}
