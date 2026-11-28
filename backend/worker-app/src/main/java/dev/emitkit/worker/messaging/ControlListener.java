package dev.emitkit.worker.messaging;

import com.rabbitmq.client.Channel;
import dev.emitkit.common.messaging.ControlMessage;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import java.io.IOException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Records each CANCEL from the API's fanout in the registry, and acks it. */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
    prefix = "emitkit.worker.listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class ControlListener {

  private final CancellationRegistry cancellation;

  ControlListener(CancellationRegistry cancellation) {
    this.cancellation = cancellation;
  }

  // One consumer: control messages are rare and tiny. Without this it would take the job listeners'
  // concurrency (spring.rabbitmq.listener.simple.concurrency).
  @RabbitListener(queues = "#{workerControlQueue.name}", concurrency = "1")
  void onControl(
      ControlMessage message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    if ("CANCEL".equals(message.type())) {
      cancellation.add(message.runId());
    }
    // Manual acks, as for every listener.
    channel.basicAck(deliveryTag, false);
  }
}
