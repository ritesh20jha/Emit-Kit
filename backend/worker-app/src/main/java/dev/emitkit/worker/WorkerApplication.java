package dev.emitkit.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the background worker application.
 *
 * <p>The worker shares the {@code :domain} database with the API: JPA entities and repositories are
 * registered in {@code config.WorkerPersistenceConfig}, and the artifact store in {@code
 * config.WorkerStorageConfig}. The worker never runs migrations; the API owns the schema.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerApplication {

  private WorkerApplication() {}

  /** Starts the worker application. */
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
