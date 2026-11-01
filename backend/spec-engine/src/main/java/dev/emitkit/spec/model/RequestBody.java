package dev.emitkit.spec.model;

import java.util.Objects;

public record RequestBody(boolean required, String contentType, Schema schema) {

  public RequestBody {
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(schema, "schema");
  }
}
