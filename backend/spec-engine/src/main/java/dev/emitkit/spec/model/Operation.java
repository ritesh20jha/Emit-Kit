package dev.emitkit.spec.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One operation. {@code securityRequirements} is the effective security requirement: the
 * operation's own, or the document's when it declares none. Each entry is one alternative, the
 * scheme names that must all be sent together; any one alternative is enough. An empty entry means
 * the operation may be called without credentials, and an empty list means it needs none. {@code
 * security} is every scheme name in those alternatives, in order, without repeats.
 */
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
    boolean deprecated,
    List<List<String>> securityRequirements) {

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
    // Null when a snapshot written before requirements existed is read back: each scheme is then
    // taken as its own alternative.
    securityRequirements =
        securityRequirements == null
            ? eachAlone(security)
            : securityRequirements.stream().map(List::copyOf).toList();
  }

  /** An operation whose requirements are one alternative per scheme in {@code security}. */
  public Operation(
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
    this(
        operationId,
        operationIdSynthesized,
        method,
        path,
        tag,
        summary,
        description,
        parameters,
        requestBody,
        responses,
        security,
        deprecated,
        eachAlone(security));
  }

  private static List<List<String>> eachAlone(List<String> security) {
    return security.stream().map(List::of).toList();
  }
}
