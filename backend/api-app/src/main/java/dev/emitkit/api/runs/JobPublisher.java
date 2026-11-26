package dev.emitkit.api.runs;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.RunStatus;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes a run's job after its creating transaction commits. A failed send marks the run FAILED.
 */
@Component
class JobPublisher {

  private static final String QUEUE_FAILURE_MESSAGE = "Could not queue run";

  private static final Logger LOG = LoggerFactory.getLogger(JobPublisher.class);

  private final RabbitTemplate rabbit;
  private final GenerationRunRepository runs;
  private final TransactionTemplate requiresNew;

  JobPublisher(
      RabbitTemplate rabbit,
      GenerationRunRepository runs,
      PlatformTransactionManager transactions) {
    this.rabbit = rabbit;
    this.runs = runs;
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Publishes after the active transaction commits, or at once when none is active. */
  void publishAfterCommit(String runId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publish(runId);
            }
          });
    } else {
      publish(runId);
    }
  }

  private void publish(String runId) {
    try {
      rabbit.convertAndSend(
          Queues.JOBS_EXCHANGE, Queues.JOBS_ROUTING_KEY, new GenerationJob(runId, 0));
    } catch (RuntimeException e) {
      LOG.error("Could not publish generation job for run {}", runId, e);
      try {
        markFailed(runId);
      } catch (RuntimeException markError) {
        LOG.error("Could not mark run {} failed", runId, markError);
        e.addSuppressed(markError);
      }
      throw new AppException(ErrorCode.INTERNAL, QUEUE_FAILURE_MESSAGE, Map.of(), e);
    }
  }

  private void markFailed(String runId) {
    requiresNew.executeWithoutResult(status ->
        runs.findById(runId)
            .ifPresent(run -> {
              run.setStatus(RunStatus.FAILED);
              run.setFailure(ErrorCode.INTERNAL.name(), QUEUE_FAILURE_MESSAGE);
              run.setFinishedAt(Instant.now());
            }));
  }
}
