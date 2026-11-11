package dev.emitkit.vcs.ratelimit;

import dev.emitkit.vcs.RateLimit;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
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
 * <p>The quota is advisory, so this store fails open. {@link #get} returns empty for a missing key
 * and for a hash with a missing or malformed field. When Redis fails (an outage, a timeout, or a
 * key of the wrong type, reported as a {@link DataAccessException}), {@link #get} also returns
 * empty and {@link #record} does nothing; each logs a WARNING and does not throw. A VCS call is
 * never failed by its quota bookkeeping.
 */
public final class RedisRateLimitStore implements RateLimitStore {

  private static final System.Logger LOG = System.getLogger(RedisRateLimitStore.class.getName());
  private static final Duration GRACE = Duration.ofSeconds(60);
  private static final String REMAINING = "remaining";
  private static final String RESET_AT = "resetAt";

  private final StringRedisTemplate redis;

  /** Stores readings through {@code redis}, whose connection factory the caller configures. */
  public RedisRateLimitStore(StringRedisTemplate redis) {
    this.redis = Objects.requireNonNull(redis, "redis");
  }

  /**
   * Stores the reading, or removes the user's key if {@code resetAt} plus one minute is not in the
   * future. A Redis failure is logged and otherwise ignored, so the caller's VCS call still
   * succeeds.
   */
  @Override
  public void record(String userId, int remaining, Instant resetAt) {
    String key = key(userId);
    Instant expiresAt = resetAt.plus(GRACE);
    try {
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
    } catch (DataAccessException e) {
      LOG.log(System.Logger.Level.WARNING, "Rate-limit reading not recorded; Redis failed", e);
    }
  }

  /**
   * The stored reading, or empty if there is none, the hash is malformed, or Redis fails. A Redis
   * failure is logged and reported as empty, so the caller proceeds as if no quota were known.
   */
  @Override
  public Optional<RateLimit> get(String userId) {
    Map<String, String> fields;
    try {
      fields = redis.<String, String>opsForHash().entries(key(userId));
    } catch (DataAccessException e) {
      LOG.log(System.Logger.Level.WARNING, "Rate-limit reading not read; Redis failed", e);
      return Optional.empty();
    }
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
