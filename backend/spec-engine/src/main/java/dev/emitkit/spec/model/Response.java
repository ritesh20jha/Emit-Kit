package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Response(String status, @Nullable String description, @Nullable Schema schema) {

  public Response {
    Objects.requireNonNull(status, "status");
  }
}
