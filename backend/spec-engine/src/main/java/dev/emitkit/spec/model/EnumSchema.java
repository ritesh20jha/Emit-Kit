package dev.emitkit.spec.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record EnumSchema(
    PrimitiveType type, List<String> values, boolean nullable, @Nullable String description)
    implements Schema {

  public EnumSchema {
    Objects.requireNonNull(type, "type");
    values = List.copyOf(Objects.requireNonNull(values, "values"));
  }
}
