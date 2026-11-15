package dev.emitkit.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Starts a throwaway RabbitMQ for the job publisher; Spring Boot wires its connection details. */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestConfig {

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbitContainer() {
    return new RabbitMQContainer("rabbitmq:4.4.0-management-alpine");
  }
}
