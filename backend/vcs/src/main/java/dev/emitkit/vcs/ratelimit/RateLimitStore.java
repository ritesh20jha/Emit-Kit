package dev.emitkit.vcs.ratelimit;

import dev.emitkit.vcs.RateLimit;
import java.time.Instant;
import java.util.Optional;

/**
 * The last quota reading per user. Implementations must be safe for concurrent use. A stored
 * reading has limit 0, meaning the limit is not tracked: only {@code remaining} and {@code resetAt}
 * are kept.
 */
public interface RateLimitStore {

  /** Records that {@code userId} has {@code remaining} requests left until {@code resetAt}. */
  void record(String userId, int remaining, Instant resetAt);

  /**
   * The last reading recorded for {@code userId}, with limit 0 (not tracked), or empty if none was
   * recorded.
   */
  Optional<RateLimit> get(String userId);
}
