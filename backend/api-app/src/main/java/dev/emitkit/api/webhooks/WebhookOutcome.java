package dev.emitkit.api.webhooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * How a delivery was answered, as the response body: a {@code status}, and a {@code reason} when
 * the delivery was ignored.
 *
 * @param status {@code pong}, {@code duplicate} or {@code ignored}
 * @param reason why the delivery was ignored; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookOutcome(String status, @Nullable String reason) {

  /** The answer to a ping, which proves the webhook reaches the API. */
  public static WebhookOutcome pong() {
    return new WebhookOutcome("pong", null);
  }

  /** The answer to a redelivery of a delivery that was already handled. */
  public static WebhookOutcome duplicate() {
    return new WebhookOutcome("duplicate", null);
  }

  /** The answer to a delivery that does not lead to any work. */
  public static WebhookOutcome ignored(String reason) {
    return new WebhookOutcome("ignored", reason);
  }
}
