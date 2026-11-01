package dev.emitkit.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a throwaway PostgreSQL 16 so the JPA and Flyway auto-configuration from {@code :domain}
 * can boot; Spring Boot wires its connection details.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:16");
  }
}
