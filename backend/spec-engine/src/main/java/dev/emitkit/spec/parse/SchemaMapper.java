package dev.emitkit.spec.parse;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Maps swagger schemas to the model. Refs stay {@link RefSchema}s and are never followed, so a
 * recursive document maps in finite time. Absent, untyped and {@code additionalProperties: true}
 * schemas map to an open object.
 */
final class SchemaMapper {

  private static final String NULL_TYPE = "null";
  private static final String REF_PREFIX = "#/components/schemas/";

  private SchemaMapper() {}

  static Schema map(io.swagger.v3.oas.models.media.@Nullable Schema<?> schema) {
    return map(schema, false, null);
  }

  /** The hints carry a null branch and a description that a oneOf passes down to its one option. */
  private static Schema map(
      io.swagger.v3.oas.models.media.@Nullable Schema<?> schema,
      boolean nullHint,
      @Nullable String descriptionHint) {
    if (schema == null) {
      return open(nullHint, descriptionHint);
    }
    boolean nullable = nullHint || nullable(schema);
    String description =
        schema.getDescription() != null ? schema.getDescription() : descriptionHint;
    if (schema.get$ref() != null) {
      return new RefSchema(refName(schema.get$ref()), nullable, description);
    }
    if (nonEmpty(schema.getOneOf())) {
      return oneOf(schema, nullable, description);
    }
    if (nonEmpty(schema.getAllOf())) {
      var parts = new ArrayList<Schema>();
      for (var part : schema.getAllOf()) {
        parts.add(map(part));
      }
      return new AllOfSchema(parts, nullable, description);
    }
    String type = concreteType(schema);
    if (type.isEmpty() && schema.getItems() != null) {
      type = "array";
    }
    if (type.isEmpty() && schema.getProperties() != null) {
      type = "object";
    }
    if (nonEmpty(schema.getEnum())) {
      return enumeration(schema, type, nullable, description);
    }
    return switch (type) {
      case "string", "integer", "number", "boolean" ->
          new PrimitiveSchema(primitive(type), schema.getFormat(), nullable, description);
      case "array" -> new ArraySchema(map(schema.getItems()), nullable, description);
      case "object" -> object(schema, nullable, description);
      default -> open(nullable, description);
    };
  }

  private static Schema oneOf(
      io.swagger.v3.oas.models.media.Schema<?> schema,
      boolean nullable,
      @Nullable String description) {
    var options = new ArrayList<io.swagger.v3.oas.models.media.Schema<?>>();
    boolean nullBranch = false;
    for (io.swagger.v3.oas.models.media.Schema<?> option : schema.getOneOf()) {
      if (types(option).equals(Set.of(NULL_TYPE))) {
        nullBranch = true;
      } else {
        options.add(option);
      }
    }
    boolean isNullable = nullable || nullBranch;
    // A oneOf that is only "X or null" is X itself, nullable.
    if (options.size() <= 1) {
      return map(options.isEmpty() ? null : options.get(0), isNullable, description);
    }
    var mapped = new ArrayList<Schema>();
    for (var option : options) {
      mapped.add(map(option));
    }
    String discriminator =
        schema.getDiscriminator() == null ? null : schema.getDiscriminator().getPropertyName();
    return new OneOfSchema(mapped, discriminator, isNullable, description);
  }

  private static EnumSchema enumeration(
      io.swagger.v3.oas.models.media.Schema<?> schema,
      String type,
      boolean nullable,
      @Nullable String description) {
    var values = new ArrayList<String>();
    boolean hasNull = false;
    for (Object value : schema.getEnum()) {
      if (value == null) {
        hasNull = true;
      } else {
        values.add(String.valueOf(value));
      }
    }
    return new EnumSchema(primitive(type), values, nullable || hasNull, description);
  }

  private static ObjectSchema object(
      io.swagger.v3.oas.models.media.Schema<?> schema,
      boolean nullable,
      @Nullable String description) {
    var properties = new LinkedHashMap<String, Schema>();
    if (schema.getProperties() != null) {
      schema.getProperties().forEach((name, property) -> properties.put(name, map(property)));
    }
    Set<String> required =
        schema.getRequired() == null ? Set.of() : new LinkedHashSet<>(schema.getRequired());
    Schema additional =
        schema.getAdditionalProperties() instanceof io.swagger.v3.oas.models.media.Schema<?> extra
            ? map(extra)
            : null;
    return new ObjectSchema(properties, required, additional, nullable, description);
  }

  private static ObjectSchema open(boolean nullable, @Nullable String description) {
    return new ObjectSchema(Map.of(), Set.of(), null, nullable, description);
  }

  private static PrimitiveType primitive(String type) {
    return switch (type) {
      case "integer" -> PrimitiveType.INTEGER;
      case "number" -> PrimitiveType.NUMBER;
      case "boolean" -> PrimitiveType.BOOLEAN;
      default -> PrimitiveType.STRING;
    };
  }

  /** The 3.0 {@code nullable: true} flag, or the 3.1 {@code "null"} member of {@code type}. */
  private static boolean nullable(io.swagger.v3.oas.models.media.Schema<?> schema) {
    return Boolean.TRUE.equals(schema.getNullable()) || types(schema).contains(NULL_TYPE);
  }

  /** The single non-null type, or "" when there is none or several. */
  private static String concreteType(io.swagger.v3.oas.models.media.Schema<?> schema) {
    var concrete = types(schema).stream().filter(t -> !NULL_TYPE.equals(t)).toList();
    return concrete.size() == 1 ? concrete.get(0) : "";
  }

  /** 3.1 declares {@code types}; 3.0 and 2.0 only set the single {@code type}. */
  private static Set<String> types(io.swagger.v3.oas.models.media.Schema<?> schema) {
    if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
      return schema.getTypes();
    }
    return schema.getType() == null ? Set.of() : Set.of(schema.getType());
  }

  /** {@code #/components/schemas/Pet} becomes {@code Pet}. */
  private static String refName(String ref) {
    return ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : ref;
  }

  private static boolean nonEmpty(@Nullable List<?> list) {
    return list != null && !list.isEmpty();
  }
}
