package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A reference to a named schema; {@code name} is a key in {@link ApiModel#schemas()}. */
public record RefSchema(String name, boolean nullable, @Nullable String description)
    implements Schema {

  public RefSchema {
    Objects.requireNonNull(name, "name");
  }
}
