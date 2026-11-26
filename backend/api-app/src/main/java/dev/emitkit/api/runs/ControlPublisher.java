package dev.emitkit.api.runs;

import dev.emitkit.common.messaging.ControlMessage;
import dev.emitkit.common.messaging.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes a cancel command to the control fanout after the active transaction commits. A failed
 * send is only logged: the run's cancel_requested flag is the durable signal the worker re-reads.
 */
@Component
class ControlPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(ControlPublisher.class);

  private final RabbitTemplate rabbit;

  ControlPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  /** Publishes after the active transaction commits, or at once when none is active. */
  void cancelAfterCommit(String runId) {
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
      rabbit.convertAndSend(Queues.CONTROL_EXCHANGE, "", new ControlMessage("CANCEL", runId));
    } catch (RuntimeException e) {
      LOG.error("Could not publish cancel for run {}", runId, e);
    }
  }
}
