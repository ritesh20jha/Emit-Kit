package dev.emitkit.worker.logs;

import dev.emitkit.domain.StepName;
import java.time.Instant;
import org.springframework.lang.Nullable;

/**
 * One log line as published to the run's Redis channel {@code run-logs:{runId}}. Serialized as JSON
 * with {@code ts} as an ISO-8601 string. {@code step} is null for a run-level line.
 */
public record LogLineMessage(
    int seq, Instant ts, String level, @Nullable StepName step, String message) {}
