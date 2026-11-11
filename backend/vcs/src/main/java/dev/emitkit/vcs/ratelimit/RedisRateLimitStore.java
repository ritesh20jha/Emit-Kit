package dev.emitkit.vcs.ratelimit;

import dev.emitkit.vcs.RateLimit;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A {@link RateLimitStore} kept in Redis. Each user's reading is a hash at {@code
 * ratelimit:{userId}} with two fields: {@code remaining} as a decimal integer and {@code resetAt}
 * as an ISO-8601 instant ({@link Instant#toString()}), which round-trips exactly. The key expires
 * one minute after {@code resetAt}, so a reader just past the reset still sees the reading.
 *
 * <p>{@link #record} writes the hash and its expiry in one MULTI/EXEC transaction, so a crash
 * cannot leave a key without an expiry. A reading whose expiry is not in the future is not stored:
 * {@code record} deletes the user's key instead, so no earlier reading outlives a newer one.
 *
 * <p>{@link #get} returns empty for a missing key and also for a hash with a missing or malformed
 * field, because the quota is advisory. Redis connection and command errors are not caught.
 */
public final class RedisRateLimitStore implements RateLimitStore {

  private static final Duration GRACE = Duration.ofSeconds(60);
  private static final String REMAINING = "remaining";
  private static final String RESET_AT = "resetAt";

  private final StringRedisTemplate redis;

  /** Stores readings through {@code redis}, whose connection factory the caller configures. */
  public RedisRateLimitStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void record(String userId, int remaining, Instant resetAt) {
    String key = key(userId);
    Instant expiresAt = resetAt.plus(GRACE);
    if (!expiresAt.isAfter(Instant.now())) {
      redis.delete(key);
      return;
    }
    Map<String, String> reading =
        Map.of(REMAINING, Integer.toString(remaining), RESET_AT, resetAt.toString());
    redis.execute(
        new SessionCallback<Object>() {
          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public Object execute(RedisOperations operations) {
            operations.multi();
            operations.opsForHash().putAll(key, reading);
            operations.expireAt(key, expiresAt);
            return operations.exec();
          }
        });
  }

  @Override
  public Optional<RateLimit> get(String userId) {
    Map<String, String> fields = redis.<String, String>opsForHash().entries(key(userId));
    String remaining = fields.get(REMAINING);
    String resetAt = fields.get(RESET_AT);
    if (remaining == null || resetAt == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new RateLimit(0, Integer.parseInt(remaining), Instant.parse(resetAt)));
    } catch (NumberFormatException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String key(String userId) {
    return "ratelimit:" + userId;
  }
}
