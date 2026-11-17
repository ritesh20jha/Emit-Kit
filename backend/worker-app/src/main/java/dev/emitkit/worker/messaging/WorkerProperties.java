package dev.emitkit.worker.messaging;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emitkit.worker.*}. The work dir is a String because Boot has no Path converter.
 *
 * @param retryTtls time a job waits in each retry queue, in attempt order
 * @param workDir root of the per-run work dirs
 * @param workDirRetention how long a finished or unknown run's dir is kept
 * @param workDirSweepInterval how often the sweeper runs (read by its placeholder)
 */
@ConfigurationProperties("emitkit.worker")
public record WorkerProperties(
    List<Duration> retryTtls,
    String workDir,
    Duration workDirRetention,
    Duration workDirSweepInterval) {}
