package dev.emitkit.spec.diff;

import java.util.Objects;

/** Identifies an operation in a diff. */
public record OpRef(String operationId, String method, String path) {

  public OpRef {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
  }
}
