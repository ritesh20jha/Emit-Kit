package dev.emitkit.worker.logs;

import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.worker.pipeline.RunLogger;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes the Redis and Postgres run logger for each run. Every logger's periodic flush shares one
 * daemon thread owned by this bean. Enabled with the pipeline, so contexts without Redis stay as
 * they are.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class RunLoggerFactory {

  private final GenerationRunLogRepository logs;
  private final StringRedisTemplate redis;
  private final ScheduledExecutorService flushes;

  public RunLoggerFactory(GenerationRunLogRepository logs, StringRedisTemplate redis) {
    this.logs = logs;
    this.redis = redis;
    this.flushes = Executors.newSingleThreadScheduledExecutor(RunLoggerFactory::daemon);
  }

  /** A logger for {@code runId} whose seq continues after the last line the run has saved. */
  public RunLogger create(String runId) {
    RedisRunLogger logger = new RedisRunLogger(runId, logs.findMaxSeqByRunId(runId), logs, redis);
    logger.startFlushing(flushes);
    return logger;
  }

  @PreDestroy
  void shutdown() {
    flushes.shutdownNow();
  }

  private static Thread daemon(Runnable task) {
    Thread thread = new Thread(task, "run-log-flush");
    thread.setDaemon(true);
    return thread;
  }
}
