package dev.emitkit.api.webhooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * How a delivery was answered, as the response body: a {@code status}, a {@code reason} when the
 * delivery was ignored, and a {@code runId} when it queued a run.
 *
 * @param status {@code pong}, {@code duplicate}, {@code ignored}, {@code queued} or {@code tagged}
 * @param reason why the delivery was ignored; null otherwise
 * @param runId the run the delivery queued; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookOutcome(String status, @Nullable String reason, @Nullable String runId) {

  /** The answer to a ping, which proves the webhook reaches the API. */
  public static WebhookOutcome pong() {
    return new WebhookOutcome("pong", null, null);
  }

  /** The answer to a redelivery of a delivery that was already handled. */
  public static WebhookOutcome duplicate() {
    return new WebhookOutcome("duplicate", null, null);
  }

  /** The answer to a delivery that does not lead to any work. */
  public static WebhookOutcome ignored(String reason) {
    return new WebhookOutcome("ignored", reason, null);
  }

  /** The answer to a delivery that queued the run {@code runId}. */
  public static WebhookOutcome queued(String runId) {
    return new WebhookOutcome("queued", null, runId);
  }

  /** The answer to a merged run whose version tags were created. */
  public static WebhookOutcome tagged() {
    return new WebhookOutcome("tagged", null, null);
  }
}
