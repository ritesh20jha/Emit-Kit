package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Parameter(
    String name, ParamLocation in, boolean required, Schema schema, @Nullable String description) {

  public Parameter {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(schema, "schema");
  }
}
