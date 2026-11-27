package dev.emitkit.spec.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A oneOf (or anyOf) of schemas. {@code discriminator} names the property that tells the options
 * apart, when the document declares one. {@code mapping} gives the discriminator value of each
 * option that the document names, as a schema name; it is empty when there is no mapping.
 */
public record OneOfSchema(
    List<Schema> options,
    @Nullable String discriminator,
    boolean nullable,
    @Nullable String description,
    Map<String, String> mapping)
    implements Schema {

  public OneOfSchema {
    options = List.copyOf(Objects.requireNonNull(options, "options"));
    // Null when a snapshot written before the mapping existed is read back.
    // A LinkedHashMap keeps the document order, which generators rely on for stable output.
    mapping =
        mapping == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
  }

  /** A oneOf with no discriminator mapping. */
  public OneOfSchema(
      List<Schema> options,
      @Nullable String discriminator,
      boolean nullable,
      @Nullable String description) {
    this(options, discriminator, nullable, description, Map.of());
  }
}
