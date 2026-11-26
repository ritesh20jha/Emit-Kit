package dev.emitkit.spec.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record SecurityScheme(
    String name,
    Kind kind,
    @Nullable String in,
    @Nullable String paramName,
    @Nullable String scheme) {

  public SecurityScheme {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
  }

  public enum Kind {
    API_KEY,
    HTTP,
    OAUTH2,
    OPENID_CONNECT
  }
}
