package dev.emitkit.common.messaging;

/**
 * Control command published to the fanout {@link Queues#CONTROL_EXCHANGE}, so every worker sees it.
 *
 * @param type the command, currently always {@code "CANCEL"}
 * @param runId the generation run the command targets
 */
public record ControlMessage(String type, String runId) {}
