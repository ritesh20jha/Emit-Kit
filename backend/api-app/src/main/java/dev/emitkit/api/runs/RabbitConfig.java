package dev.emitkit.api.runs;

import dev.emitkit.common.messaging.Queues;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sends job messages as JSON, the format the worker's listener reads. The API only publishes, so it
 * declares no queues; the worker owns the topology.
 */
@Configuration(proxyBeanMethods = false)
class RabbitConfig {

  /** The shared message types are the only classes the worker will deserialize, as on its side. */
  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(Queues.class.getPackageName());
  }
}
