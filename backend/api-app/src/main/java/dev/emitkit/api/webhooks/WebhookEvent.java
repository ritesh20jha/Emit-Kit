package dev.emitkit.api.webhooks;

import tools.jackson.databind.JsonNode;

/**
 * A GitHub delivery whose signature has been verified.
 *
 * @param type the {@code X-GitHub-Event} name, such as {@code push}
 * @param deliveryId the {@code X-GitHub-Delivery} id, which identifies a delivery and its
 *     redeliveries
 * @param payload the parsed JSON body
 */
public record WebhookEvent(String type, String deliveryId, JsonNode payload) {}
