package dev.emitkit.api;

import dev.emitkit.domain.User;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the REST API application. The entities and repositories live in {@code :domain},
 * outside this package, so that package is registered for JPA scanning as well.
 */
@SpringBootApplication
@AutoConfigurationPackage(basePackageClasses = User.class)
public class ApiApplication {

  private ApiApplication() {}

  /** Starts the API application. */
  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
