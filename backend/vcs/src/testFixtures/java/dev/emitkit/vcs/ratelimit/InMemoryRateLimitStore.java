package dev.emitkit.vcs.ratelimit;

import dev.emitkit.vcs.RateLimit;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the last reading per user in memory, with limit 0 as the store contract allows. */
public final class InMemoryRateLimitStore implements RateLimitStore {

  private final Map<String, RateLimit> quotas = new ConcurrentHashMap<>();

  @Override
  public void record(String userId, int remaining, Instant resetAt) {
    quotas.put(userId, new RateLimit(0, remaining, resetAt));
  }

  @Override
  public Optional<RateLimit> get(String userId) {
    return Optional.ofNullable(quotas.get(userId));
  }
}
