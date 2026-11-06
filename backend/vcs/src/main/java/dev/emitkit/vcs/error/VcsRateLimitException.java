package dev.emitkit.vcs.error;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** The provider's API quota is exhausted. The worker retries after {@link #resetAt()}. */
public class VcsRateLimitException extends TransientException {

  private final Instant resetAt;

  /**
   * Creates the exception. {@code resetAt} is also exposed as the {@code resetAt} extra property.
   */
  public VcsRateLimitException(String detail, Instant resetAt) {
    super(
        ErrorCode.VCS_RATE_LIMITED,
        detail,
        Map.of("resetAt", Objects.requireNonNull(resetAt, "resetAt").toString()),
        null);
    this.resetAt = resetAt;
  }

  /** The time at which the quota resets. */
  public Instant resetAt() {
    return resetAt;
  }
}
