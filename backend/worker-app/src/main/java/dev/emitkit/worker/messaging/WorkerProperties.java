package dev.emitkit.worker.messaging;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code emitkit.worker.*}; the work dir is a String because Boot has no Path converter. */
@ConfigurationProperties("emitkit.worker")
public record WorkerProperties(
    List<Duration> retryTtls,
    String workDir,
    Duration workDirRetention,
    Duration workDirSweepInterval) {}
