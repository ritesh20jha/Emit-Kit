package dev.emitkit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the REST API application. */
@SpringBootApplication
public class ApiApplication {

  private ApiApplication() {}

  /** Starts the API application. */
  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
