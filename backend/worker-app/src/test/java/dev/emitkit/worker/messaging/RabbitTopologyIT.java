package dev.emitkit.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.common.messaging.ControlMessage;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

// runc under the test profile: the sandbox startup check must not need a sandbox daemon.
@SpringBootTest(
    properties = {
      "emitkit.worker.listener.enabled=false",
      "emitkit.worker.retry-ttls=200ms,200ms,200ms",
      "emitkit.vault.cipher=local",
      "emitkit.sandbox.runtime=runc"
    })
@ActiveProfiles("test")
@Testcontainers
class RabbitTopologyIT {

  @Container @ServiceConnection
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine");

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private ControlRecorder controlRecorder;

  @Test
  void retryQueueDeadLettersBackToJobs() {
    rabbitTemplate.convertAndSend("", "generation.retry.10s", new GenerationJob("r1", 1));

    Object message = rabbitTemplate.receiveAndConvert(Queues.JOBS_QUEUE, 5000);

    assertThat(message).isEqualTo(new GenerationJob("r1", 1));
  }

  @Test
  void controlFanoutReachesWorkerQueue() throws InterruptedException {
    rabbitTemplate.convertAndSend(Queues.CONTROL_EXCHANGE, "", new ControlMessage("CANCEL", "r1"));

    assertThat(controlRecorder.received.poll(5, TimeUnit.SECONDS))
        .isEqualTo(new ControlMessage("CANCEL", "r1"));
  }

  @Test
  void retryQueueForAttempt() {
    assertThat(Queues.retryQueueFor(1)).isEqualTo("generation.retry.10s");
    assertThat(Queues.retryQueueFor(3)).isEqualTo("generation.retry.300s");
  }

  /** Records control messages received on the worker's own exclusive control queue. */
  static final class ControlRecorder {
    final BlockingQueue<ControlMessage> received = new LinkedBlockingQueue<>();

    @RabbitListener(queues = "#{workerControlQueue.name}")
    public void onControl(ControlMessage message) {
      received.add(message);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ControlRecorderConfig {

    @Bean
    ControlRecorder controlRecorder() {
      return new ControlRecorder();
    }
  }
}
