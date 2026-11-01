package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record ArraySchema(Schema items, boolean nullable, @Nullable String description)
    implements Schema {

  public ArraySchema {
    Objects.requireNonNull(items, "items");
  }
}
