package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Server(String url, @Nullable String description) {

  public Server {
    Objects.requireNonNull(url, "url");
  }
}
