package dev.emitkit.worker.messaging;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emitkit.worker.*}.
 *
 * @param retryTtls time a job waits in each retry queue, in attempt order; must match {@code
 *     Queues.RETRY_QUEUES}
 */
@ConfigurationProperties("emitkit.worker")
public record WorkerProperties(List<Duration> retryTtls) {}
