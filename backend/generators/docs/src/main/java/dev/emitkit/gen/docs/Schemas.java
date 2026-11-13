package dev.emitkit.gen.docs;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Schema text for the docs tables, and sample JSON values for request body examples. */
final class Schemas {

  private Schemas() {}

  /** A property row of a schema table. Every cell is already escaped for Markdown. */
  record Field(String name, String type, String required, String description) {}

  /** A short label such as {@code integer (int64)}, {@code array of Pet} or {@code Pet}. */
  static String typeText(Schema schema) {
    return switch (schema) {
      case PrimitiveSchema p -> lower(p.type().name()) + format(p.format());
      case EnumSchema e -> lower(e.type().name()) + " enum: " + String.join(", ", e.values());
      case RefSchema r -> r.name();
      case ArraySchema a -> "array of " + typeText(a.items());
      case ObjectSchema o -> "object";
      case OneOfSchema o -> join(o.options(), " or ");
      case AllOfSchema a -> join(a.parts(), " and ");
    };
  }

  /**
   * The properties of {@code schema} as table rows, in declaration order. References are resolved
   * through {@code schemas}, array items and all-of parts contribute their fields, and any other
   * schema has no rows. A reference already being resolved contributes none.
   */
  static List<Field> fields(Schema schema, Map<String, Schema> schemas) {
    return fields(schema, schemas, new HashSet<>());
  }

  private static List<Field> fields(Schema schema, Map<String, Schema> schemas, Set<String> seen) {
    return switch (schema) {
      case ObjectSchema o ->
          o.properties().entrySet().stream()
              .map(e ->
                  new Field(
                      Markdown.cell(e.getKey()),
                      Markdown.cell(typeText(e.getValue())),
                      yesNo(o.required().contains(e.getKey())),
                      Markdown.cell(e.getValue().description())))
              .toList();
      case ArraySchema a -> fields(a.items(), schemas, seen);
      case AllOfSchema a ->
          a.parts().stream().flatMap(p -> fields(p, schemas, seen).stream()).toList();
      case RefSchema r -> {
        Schema target = schemas.get(r.name());
        if (target == null || !seen.add(r.name())) {
          yield List.of();
        }
        try {
          yield fields(target, schemas, seen);
        } finally {
          seen.remove(r.name());
        }
      }
      default -> List.of();
    };
  }

  /**
   * A sample JSON value for {@code schema}: object properties in declaration order, first enum or
   * option values. A reference that is already being expanded becomes an empty object.
   */
  static Object sample(Schema schema, Map<String, Schema> schemas) {
    return sample(schema, schemas, new HashSet<>());
  }

  private static Object sample(Schema schema, Map<String, Schema> schemas, Set<String> expanding) {
    return switch (schema) {
      case PrimitiveSchema p -> primitive(p.type());
      case EnumSchema e -> e.values().isEmpty() ? null : value(e.type(), e.values().getFirst());
      case ObjectSchema o -> {
        Map<String, Object> out = new LinkedHashMap<>();
        o.properties().forEach((name, s) -> out.put(name, sample(s, schemas, expanding)));
        yield out;
      }
      case ArraySchema a -> Collections.singletonList(sample(a.items(), schemas, expanding));
      case RefSchema r -> reference(r.name(), schemas, expanding);
      case OneOfSchema o ->
          o.options().isEmpty() ? null : sample(o.options().getFirst(), schemas, expanding);
      case AllOfSchema a -> {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Schema part : a.parts()) {
          if (sample(part, schemas, expanding) instanceof Map<?, ?> values) {
            values.forEach((k, v) -> merged.put(String.valueOf(k), v));
          }
        }
        yield merged;
      }
    };
  }

  private static Object reference(String name, Map<String, Schema> schemas, Set<String> expanding) {
    Schema target = schemas.get(name);
    if (target == null || !expanding.add(name)) {
      return Map.of();
    }
    try {
      return sample(target, schemas, expanding);
    } finally {
      expanding.remove(name);
    }
  }

  private static Object primitive(PrimitiveType type) {
    return switch (type) {
      case STRING -> "string";
      case INTEGER -> 1;
      case NUMBER -> 1.0;
      case BOOLEAN -> true;
    };
  }

  private static Object value(PrimitiveType type, String text) {
    return switch (type) {
      case STRING -> text;
      case BOOLEAN -> Boolean.parseBoolean(text);
      case INTEGER, NUMBER -> {
        try {
          yield new BigDecimal(text);
        } catch (NumberFormatException e) {
          yield text;
        }
      }
    };
  }

  static String yesNo(boolean value) {
    return value ? "Yes" : "No";
  }

  private static String join(List<Schema> schemas, String separator) {
    return schemas.stream().map(Schemas::typeText).collect(Collectors.joining(separator));
  }

  private static String format(String format) {
    return format == null ? "" : " (" + format + ")";
  }

  static String lower(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
