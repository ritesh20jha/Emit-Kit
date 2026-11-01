package dev.emitkit.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Titles the OpenAPI document that springdoc serves at /v3/api-docs. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /** The API description; paths are added by springdoc from the controllers. */
  @Bean
  public OpenAPI emitkitOpenApi() {
    return new OpenAPI().info(new Info().title("EmitKit API").version("v1"));
  }
}
