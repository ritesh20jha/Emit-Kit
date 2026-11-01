package dev.emitkit.spec.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record OneOfSchema(
    List<Schema> options,
    @Nullable String discriminator,
    boolean nullable,
    @Nullable String description)
    implements Schema {

  public OneOfSchema {
    options = List.copyOf(Objects.requireNonNull(options, "options"));
  }
}
