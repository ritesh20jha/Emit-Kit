package dev.emitkit.common.messaging;

/**
 * Work item published to {@link Queues#JOBS_EXCHANGE}.
 *
 * @param runId the generation run this job belongs to
 * @param attempt the attempt number, starting at 1
 */
public record GenerationJob(String runId, int attempt) {}
