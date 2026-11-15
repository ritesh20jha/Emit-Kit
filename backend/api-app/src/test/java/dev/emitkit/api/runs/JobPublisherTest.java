package dev.emitkit.api.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRunRepository;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** A failed send answers INTERNAL even when the run cannot be marked FAILED either. */
class JobPublisherTest {

  @Test
  void publishFailure_withMarkFailureToo_stillAnswersInternal() {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    GenerationRunRepository runs = mock(GenerationRunRepository.class);
    AmqpConnectException sendError = new AmqpConnectException(new IOException("broker down"));
    doThrow(sendError)
        .when(rabbit)
        .convertAndSend(
            eq(Queues.JOBS_EXCHANGE), eq(Queues.JOBS_ROUTING_KEY), any(GenerationJob.class));
    when(runs.findById("run-1")).thenThrow(new IllegalStateException("database down"));
    JobPublisher publisher = new JobPublisher(rabbit, runs, mock(PlatformTransactionManager.class));

    assertThatThrownBy(() -> publisher.publishAfterCommit("run-1"))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL);
              assertThat(e.getMessage()).isEqualTo("Could not queue run");
              assertThat(e.getCause()).isSameAs(sendError);
            });
  }
}
