package dev.emitkit.api.webhooks;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Remembers the GitHub deliveries that were already handled. GitHub redelivers a delivery it
 * considers failed, so a second copy must not start the same work again. Each id is kept for a day
 * at {@code webhook:delivery:{deliveryId}}.
 */
@Component
public class DeliveryDeduper {

  private static final Duration TTL = Duration.ofHours(24);

  private static final String PREFIX = "webhook:delivery:";

  private final StringRedisTemplate redis;

  public DeliveryDeduper(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Claims a delivery id. Only the first claim of an id succeeds.
   *
   * @return true when the id was not claimed before; false for a redelivery
   */
  public boolean claim(String deliveryId) {
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + deliveryId, "1", TTL));
  }
}
