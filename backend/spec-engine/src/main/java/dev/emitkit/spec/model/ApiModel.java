package dev.emitkit.spec.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Language-neutral, immutable model of an OpenAPI document. Collection order is preserved. */
public record ApiModel(
    Info info,
    List<Server> servers,
    Map<String, SecurityScheme> securitySchemes,
    List<Tag> tags,
    List<Operation> operations,
    Map<String, Schema> schemas) {

  public ApiModel {
    Objects.requireNonNull(info, "info");
    servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
    securitySchemes =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(securitySchemes, "securitySchemes")));
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    schemas =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(schemas, "schemas")));
  }
}
