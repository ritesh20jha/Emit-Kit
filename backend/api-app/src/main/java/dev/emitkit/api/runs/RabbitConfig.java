package dev.emitkit.api.runs;

import dev.emitkit.common.messaging.Queues;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sends job messages as JSON, the format the worker reads. */
@Configuration(proxyBeanMethods = false)
class RabbitConfig {

  /** The jobs exchange and queue. Must stay identical to the worker's declaration. */
  @Bean
  Declarables jobsTopology() {
    DirectExchange exchange = new DirectExchange(Queues.JOBS_EXCHANGE);
    Queue queue = QueueBuilder.durable(Queues.JOBS_QUEUE).build();
    return new Declarables(
        exchange, queue, BindingBuilder.bind(queue).to(exchange).with(Queues.JOBS_ROUTING_KEY));
  }

  /**
   * The control fanout that cancel commands go to. Must stay identical to the worker's declaration.
   */
  @Bean
  Declarables controlTopology() {
    return new Declarables(new FanoutExchange(Queues.CONTROL_EXCHANGE));
  }

  /** Trusts the shared message types only, as the worker does. */
  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(Queues.class.getPackageName());
  }
}
