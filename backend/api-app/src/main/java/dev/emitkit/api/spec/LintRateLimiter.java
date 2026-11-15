package dev.emitkit.api.spec;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Allows each signed-in user {@value #LIMIT} lint requests a minute. The count is a Redis counter
 * at {@code ratelimit:lint:{userId}:{epochMinute}}, which expires a minute after its first
 * increment.
 *
 * <p>The limit is advisory, so it fails open: when Redis fails, the request is allowed and a
 * WARNING is logged.
 */
@Component
public class LintRateLimiter {

  static final int LIMIT = 30;

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final System.Logger LOG = System.getLogger(LintRateLimiter.class.getName());

  private final StringRedisTemplate redis;

  public LintRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Counts one lint request by {@code userId}.
   *
   * @throws AppException RATE_LIMITED when the user has already made {@value #LIMIT} requests in
   *     this minute
   */
  public void acquire(String userId) {
    String key = "ratelimit:lint:" + userId + ":" + Instant.now().getEpochSecond() / 60;
    Long count;
    try {
      count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, WINDOW);
      }
    } catch (DataAccessException e) {
      LOG.log(System.Logger.Level.WARNING, "Lint rate limit not counted; Redis failed", e);
      return;
    }
    if (count != null && count > LIMIT) {
      throw new AppException(ErrorCode.RATE_LIMITED, "too many lint requests; try again later");
    }
  }
}
