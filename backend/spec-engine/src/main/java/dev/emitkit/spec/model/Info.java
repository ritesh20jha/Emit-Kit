package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Info(
    String title, String version, @Nullable String description, @Nullable String contactEmail) {

  public Info {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(version, "version");
  }
}
