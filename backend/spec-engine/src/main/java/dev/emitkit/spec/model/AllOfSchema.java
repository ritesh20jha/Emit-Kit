package dev.emitkit.spec.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record AllOfSchema(List<Schema> parts, boolean nullable, @Nullable String description)
    implements Schema {

  public AllOfSchema {
    parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
  }
}
