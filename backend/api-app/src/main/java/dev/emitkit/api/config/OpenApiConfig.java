package dev.emitkit.api.config;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Titles the OpenAPI document that springdoc serves at /v3/api-docs. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /**
   * The prefix the controllers map. The document leaves it out, see {@link #apiBaseCustomizer()}.
   */
  static final String API_BASE = "/api/v1";

  static {
    // swagger-core reads this flag while it resolves models, so set it before the first document is
    // built: enums such as Role become named component schemas instead of inline string unions.
    ModelResolver.enumsAsRef = true;
  }

  /** The API description; paths are added by springdoc from the controllers. */
  @Bean
  public OpenAPI emitkitOpenApi() {
    return new OpenAPI().info(new Info().title("EmitKit API").version("v1"));
  }

  /**
   * Makes paths relative to {@code /api/v1} and names that as the only server, so the generated
   * client is built with {@code baseUrl: "/api/v1"} and calls {@code GET("/me")}. Controllers keep
   * their full mappings.
   */
  @Bean
  public OpenApiCustomizer apiBaseCustomizer() {
    return openApi -> {
      Paths relative = new Paths();
      openApi
          .getPaths()
          .forEach((path, item) ->
              relative.addPathItem(
                  path.startsWith(API_BASE) ? path.substring(API_BASE.length()) : path, item));
      openApi.setPaths(relative);
      openApi.setServers(List.of(new Server().url(API_BASE)));
    };
  }

  /**
   * Rewrites a nullable reference, {@code {"$ref": X, "type": [t, "null"]}}, as {@code {"oneOf":
   * [{"$ref": X}, {"type": "null"}]}}. Generated clients drop a null type that sits beside a $ref,
   * so only this form types the property as {@code X | null}.
   */
  @Bean
  public OpenApiCustomizer nullableReferenceCustomizer() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      openApi
          .getComponents()
          .getSchemas()
          .values()
          .forEach(schema -> {
            if (schema.getProperties() != null) {
              schema.getProperties().replaceAll((name, property) -> nullable((Schema<?>) property));
            }
          });
    };
  }

  private static Schema<?> nullable(Schema<?> property) {
    if (property.get$ref() == null
        || property.getTypes() == null
        || !property.getTypes().contains("null")) {
      return property;
    }
    List<Schema> branches =
        List.of(new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null")));
    return new ComposedSchema().oneOf(branches);
  }
}
