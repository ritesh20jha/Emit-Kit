package dev.emitkit.spec.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record ObjectSchema(
    Map<String, Schema> properties,
    Set<String> required,
    @Nullable Schema additionalProperties,
    boolean nullable,
    @Nullable String description)
    implements Schema {

  public ObjectSchema {
    properties =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(properties, "properties")));
    required =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(Objects.requireNonNull(required, "required")));
  }
}
