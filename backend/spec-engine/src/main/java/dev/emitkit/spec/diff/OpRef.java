package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/** Identifies an operation in a diff. */
public record OpRef(
    @Schema(requiredMode = REQUIRED) String operationId,
    @Schema(requiredMode = REQUIRED) String method,
    @Schema(requiredMode = REQUIRED) String path) {

  public OpRef {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
  }
}
