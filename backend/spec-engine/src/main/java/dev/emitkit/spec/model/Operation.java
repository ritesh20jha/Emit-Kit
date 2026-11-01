package dev.emitkit.spec.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Operation(
    String operationId,
    boolean operationIdSynthesized,
    String method,
    String path,
    String tag,
    @Nullable String summary,
    @Nullable String description,
    List<Parameter> parameters,
    @Nullable RequestBody requestBody,
    Map<String, Response> responses,
    List<String> security,
    boolean deprecated) {

  public Operation {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(tag, "tag");
    parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    responses =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(responses, "responses")));
    security = List.copyOf(Objects.requireNonNull(security, "security"));
  }
}
