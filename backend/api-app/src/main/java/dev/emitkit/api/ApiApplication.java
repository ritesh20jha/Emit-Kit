package dev.emitkit.api;

import dev.emitkit.domain.User;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Entry point for the REST API application. The entities and repositories live in {@code :domain},
 * outside this package, so that package is registered for JPA scanning as well.
 *
 * <p>The scan repeats the defaults of {@code SpringBootApplication} and adds one exclusion:
 * management context configurations (the management-port probes) are registered only in the
 * management child context, so they are never served on the main port.
 */
@SpringBootApplication
@AutoConfigurationPackage(basePackageClasses = User.class)
@ComponentScan(
    excludeFilters = {
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.ANNOTATION,
          classes = ManagementContextConfiguration.class)
    })
public class ApiApplication {

  private ApiApplication() {}

  /** Starts the API application. */
  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
