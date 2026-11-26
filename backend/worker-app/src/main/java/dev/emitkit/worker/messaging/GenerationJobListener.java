package dev.emitkit.worker.messaging;

import com.rabbitmq.client.Channel;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.worker.logs.RunLoggerFactory;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineRunner;
import dev.emitkit.worker.pipeline.RunLogger;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each delivery of a generation job. Transient failures are routed after the lock is released.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
    prefix = "emitkit.worker.listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class GenerationJobListener {

  private static final Logger LOG = LoggerFactory.getLogger(GenerationJobListener.class);
  static final Duration REQUEUE_DELAY = Duration.ofSeconds(5);

  private final PipelineRunner runner;
  private final RunLock locks;
  private final RetryRouter retries;
  private final CancelCleanup cancels;
  private final GenerationRunRepository runs;
  private final RunLoggerFactory loggers;
  private final TransactionTemplate transactions;

  GenerationJobListener(
      PipelineRunner runner,
      RunLock locks,
      RetryRouter retries,
      CancelCleanup cancels,
      GenerationRunRepository runs,
      RunLoggerFactory loggers,
      PlatformTransactionManager transactionManager) {
    this.runner = runner;
    this.locks = locks;
    this.retries = retries;
    this.cancels = cancels;
    this.runs = runs;
    this.loggers = loggers;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @RabbitListener(queues = Queues.JOBS_QUEUE)
  void onJob(GenerationJob job, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    String runId = job.runId();
    MDC.put("runId", runId);
    try {
      LOG.info("Received generation job runId={} attempt={}", runId, job.attempt());
      Optional<RunLock.Lease> lease = locks.tryAcquire(runId);
      if (lease.isEmpty()) {
        if (isUnfinished(runId)) {
          LOG.info("generation run {} is held by another delivery; re-parking this one", runId);
          retries.repark(job);
        } else {
          LOG.info("generation run {} is held and already finished; dropping this one", runId);
        }
        channel.basicAck(deliveryTag, false);
        return;
      }
      TransientException transientFailure;
      try (RunLock.Lease held = lease.get()) {
        transientFailure = runOnce(runId);
      }
      if (transientFailure != null) {
        retries.route(job, transientFailure);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException e) {
      // Unacked, the delivery would hold the only prefetch slot until restart.
      LOG.error("generation job failed before it was handled; requeuing it", e);
      pauseBeforeRequeue();
      channel.basicNack(deliveryTag, false, true);
    } finally {
      MDC.remove("runId");
    }
  }

  /** Whether the run exists and has not finished, so a delivery that lost the lock must retry. */
  private boolean isUnfinished(String runId) {
    return runs.findById(runId).map(run -> !run.getStatus().isTerminal()).orElse(false);
  }

  private static void pauseBeforeRequeue() {
    try {
      Thread.sleep(REQUEUE_DELAY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Runs the pipeline once; returns the transient failure to retry, or null when the run is
   * handled.
   */
  private TransientException runOnce(String runId) {
    try {
      runner.run(runId);
      return null;
    } catch (TransientException e) {
      LOG.warn("generation run {} failed transiently: {}", runId, e.getMessage());
      return e;
    } catch (CancelledException e) {
      cancels.cancelled(runId);
      return null;
    } catch (RuntimeException e) {
      LOG.error("generation run {} failed unexpectedly", runId, e);
      failInternal(runId);
      return null;
    }
  }

  /** Fails a run whose attempt threw something unexpected. The detail stays in the worker log. */
  private void failInternal(String runId) {
    boolean failed =
        RunUpdates.ifRunning(
            transactions,
            runs,
            runId,
            r -> {
              r.setStatus(RunStatus.FAILED);
              r.setFailure(ErrorCode.INTERNAL.name(), "Internal error");
              r.setFinishedAt(Instant.now());
            });
    if (failed) {
      RunLogger log = loggers.create(runId);
      log.error("Internal error");
      log.done();
    }
  }
}
