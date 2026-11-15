package dev.emitkit.api.runs;

import dev.emitkit.common.messaging.Queues;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sends job messages as JSON, the format the worker reads. The worker owns the topology. */
@Configuration(proxyBeanMethods = false)
class RabbitConfig {

  /** Trusts the shared message types only, as the worker does. */
  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(Queues.class.getPackageName());
  }
}
