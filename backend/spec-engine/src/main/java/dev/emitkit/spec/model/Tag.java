package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Tag(String name, @Nullable String description) {

  public Tag {
    Objects.requireNonNull(name, "name");
  }
}
