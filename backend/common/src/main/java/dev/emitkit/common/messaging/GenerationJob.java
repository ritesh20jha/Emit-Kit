package dev.emitkit.common.messaging;

/**
 * Work item published to {@link Queues#JOBS_EXCHANGE}.
 *
 * @param runId the generation run this job belongs to
 * @param attempt the number of earlier retries; 0 for the first delivery
 */
public record GenerationJob(String runId, int attempt) {}
