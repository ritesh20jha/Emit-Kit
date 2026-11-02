package dev.emitkit.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point for the background worker application.
 *
 * <p>The worker has no database yet, but {@code :domain} puts JPA and Flyway on the classpath, so
 * the DataSource auto-configuration (which they depend on) is excluded.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WorkerApplication {

  private WorkerApplication() {}

  /** Starts the worker application. */
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
