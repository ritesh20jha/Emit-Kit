package dev.emitkit.worker.messaging;

import dev.emitkit.common.messaging.Queues;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the jobs, retry, dead-letter and control topology that the worker consumes from. */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfig {

  /** Exclusive, auto-delete queue that receives every control message while the worker runs. */
  @Bean
  AnonymousQueue workerControlQueue() {
    return new AnonymousQueue();
  }

  @Bean
  Declarables rabbitTopology(WorkerProperties properties, AnonymousQueue workerControlQueue) {
    List<Duration> ttls = properties.retryTtls();
    if (ttls.size() != Queues.RETRY_QUEUES.size()) {
      throw new IllegalStateException(
          "emitkit.worker.retry-ttls needs " + Queues.RETRY_QUEUES.size() + " values");
    }

    DirectExchange jobsExchange = new DirectExchange(Queues.JOBS_EXCHANGE);
    Queue jobsQueue = QueueBuilder.durable(Queues.JOBS_QUEUE).build();
    FanoutExchange controlExchange = new FanoutExchange(Queues.CONTROL_EXCHANGE);

    List<Declarable> declarables = new ArrayList<>();
    declarables.add(jobsExchange);
    declarables.add(jobsQueue);
    declarables.add(BindingBuilder.bind(jobsQueue).to(jobsExchange).with(Queues.JOBS_ROUTING_KEY));
    declarables.add(QueueBuilder.durable(Queues.DLQ).build());
    declarables.add(controlExchange);
    declarables.add(BindingBuilder.bind(workerControlQueue).to(controlExchange));

    for (int i = 0; i < ttls.size(); i++) {
      declarables.add(
          QueueBuilder.durable(Queues.RETRY_QUEUES.get(i))
              .ttl((int) ttls.get(i).toMillis())
              .deadLetterExchange(Queues.JOBS_EXCHANGE)
              .deadLetterRoutingKey(Queues.JOBS_ROUTING_KEY)
              .build());
    }
    return new Declarables(declarables);
  }

  /**
   * JSON for every message body. The shared message types are the only classes the worker will
   * deserialize from a {@code __TypeId__} header, so only their package is trusted.
   */
  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(Queues.class.getPackageName());
  }
}
