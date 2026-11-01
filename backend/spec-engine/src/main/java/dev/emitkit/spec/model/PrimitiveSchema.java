package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record PrimitiveSchema(
    PrimitiveType type, @Nullable String format, boolean nullable, @Nullable String description)
    implements Schema {

  public PrimitiveSchema {
    Objects.requireNonNull(type, "type");
  }
}
