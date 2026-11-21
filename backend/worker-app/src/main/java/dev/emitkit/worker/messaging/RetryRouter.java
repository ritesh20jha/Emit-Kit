package dev.emitkit.worker.messaging;

import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.worker.logs.RunLoggerFactory;
import dev.emitkit.worker.pipeline.RunLogger;
import java.time.Duration;
import java.time.Instant;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Routes a delivery whose attempt failed transiently: parks the job in the retry queue for its
 * attempt, or dead-letters it once the attempts are spent and fails the run. Called after the run's
 * lock is released, so the retry is not dropped as a duplicate of the attempt that scheduled it.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
class RetryRouter {

  private final GenerationRunRepository runs;
  private final RabbitTemplate rabbit;
  private final TransactionTemplate transactions;
  private final RunLoggerFactory loggers;
  private final WorkerProperties properties;

  RetryRouter(
      GenerationRunRepository runs,
      RabbitTemplate rabbit,
      PlatformTransactionManager transactionManager,
      RunLoggerFactory loggers,
      WorkerProperties properties) {
    this.runs = runs;
    this.rabbit = rabbit;
    this.transactions = new TransactionTemplate(transactionManager);
    this.loggers = loggers;
    this.properties = properties;
  }

  /** Routes {@code job}, whose delivery failed with {@code failure}. */
  void route(GenerationJob job, TransientException failure) {
    int next = job.attempt() + 1;
    if (next > Queues.MAX_ATTEMPTS) {
      giveUp(job, failure);
    } else {
      retry(job, next);
    }
  }

  private void retry(GenerationJob job, int next) {
    String runId = job.runId();
    if (!RunUpdates.ifRunning(transactions, runs, runId, r -> r.setAttempt(next))) {
      return;
    }
    rabbit.convertAndSend("", Queues.retryQueueFor(next), new GenerationJob(runId, next));
    RunLogger log = loggers.create(runId);
    log.warn("Retrying in " + describe(properties.retryTtls().get(next - 1)));
    log.flush();
  }

  private void giveUp(GenerationJob job, TransientException failure) {
    String runId = job.runId();
    String message = "Gave up after " + Queues.MAX_ATTEMPTS + " attempts: " + failure.getMessage();
    boolean failed =
        RunUpdates.ifRunning(
            transactions,
            runs,
            runId,
            r -> {
              r.setStatus(RunStatus.FAILED);
              r.setFailure(failure.code().name(), message);
              r.setFinishedAt(Instant.now());
            });
    if (!failed) {
      return;
    }
    rabbit.convertAndSend("", Queues.DLQ, job);
    RunLogger log = loggers.create(runId);
    log.error(message);
    log.done();
  }

  /** A retry delay as the log shows it: whole seconds, or milliseconds for a sub-second delay. */
  private static String describe(Duration delay) {
    return delay.toMillis() % 1000 == 0 ? delay.toSeconds() + "s" : delay.toMillis() + "ms";
  }
}
