package dev.emitkit.worker.messaging;

import com.rabbitmq.client.Channel;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Skeleton consumer for generation jobs: logs each job and acknowledges it. The real generation
 * work is added in WRK-11.
 */
@Component
@ConditionalOnProperty(
    prefix = "emitkit.worker.listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class GenerationJobListener {

  private static final Logger LOG = LoggerFactory.getLogger(GenerationJobListener.class);

  @RabbitListener(queues = Queues.JOBS_QUEUE)
  void onJob(GenerationJob job, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws IOException {
    LOG.info("Received generation job runId={} attempt={}", job.runId(), job.attempt());
    channel.basicAck(deliveryTag, false);
  }
}
