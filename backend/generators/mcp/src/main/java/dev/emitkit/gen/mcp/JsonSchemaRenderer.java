package dev.emitkit.gen.mcp;

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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts model schemas to JSON Schema (draft 2020-12) value trees. {@code $ref}s are inlined. A
 * reference to a schema that is already being expanded higher on the same path is a cycle, and
 * renders as {@code {}} so recursive schemas stay finite. Keys follow model order.
 */
final class JsonSchemaRenderer {

  private static final Pattern INTEGER = Pattern.compile("-?\\d+");
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

  private final Map<String, Schema> schemas;
  private final Set<String> expanding = new HashSet<>();

  JsonSchemaRenderer(Map<String, Schema> schemas) {
    this.schemas = schemas;
  }

  /** {@code schema} as a value tree, with {@code description} replacing its own when non-null. */
  Map<String, Object> render(Schema schema, String description) {
    Map<String, Object> out = node(schema);
    if (description != null) {
      out.put("description", description);
    }
    return out;
  }

  private Map<String, Object> node(Schema schema) {
    Map<String, Object> out =
        switch (schema) {
          case PrimitiveSchema p -> {
            Map<String, Object> m = typed(typeName(p.type()), p.nullable());
            if (p.format() != null) {
              m.put("format", p.format());
            }
            yield m;
          }
          case EnumSchema e -> {
            Map<String, Object> m = typed(typeName(e.type()), e.nullable());
            m.put("enum", enumValues(e));
            yield m;
          }
          case ArraySchema a -> {
            Map<String, Object> m = typed("array", a.nullable());
            m.put("items", node(a.items()));
            yield m;
          }
          case ObjectSchema o -> {
            Map<String, Object> m = typed("object", o.nullable());
            m.put("properties", properties(o));
            m.put("required", List.copyOf(o.required()));
            if (o.additionalProperties() != null) {
              m.put("additionalProperties", node(o.additionalProperties()));
            }
            yield m;
          }
          case OneOfSchema o -> wrapNullable(ordered("oneOf", nodes(o.options())), o.nullable());
          case AllOfSchema a -> wrapNullable(ordered("allOf", nodes(a.parts())), a.nullable());
          case RefSchema r -> wrapNullable(reference(r), r.nullable());
        };
    if (schema.description() != null) {
      out.put("description", schema.description());
    }
    return out;
  }

  private Map<String, Object> reference(RefSchema ref) {
    Schema target = schemas.get(ref.name());
    if (target == null || !expanding.add(ref.name())) {
      // Unknown name, or a cycle back to a schema that is still being expanded.
      return new LinkedHashMap<>();
    }
    try {
      return node(target);
    } finally {
      expanding.remove(ref.name());
    }
  }

  private Map<String, Object> properties(ObjectSchema object) {
    Map<String, Object> out = new LinkedHashMap<>();
    object.properties().forEach((name, schema) -> out.put(name, node(schema)));
    return out;
  }

  private List<Object> nodes(List<Schema> schemas) {
    List<Object> out = new ArrayList<>();
    for (Schema schema : schemas) {
      out.add(node(schema));
    }
    return out;
  }

  /** A nullable oneOf or allOf: the value is the composite or null. */
  private static Map<String, Object> wrapNullable(Map<String, Object> core, boolean nullable) {
    return nullable ? ordered("anyOf", List.of(core, ordered("type", "null"))) : core;
  }

  private static Map<String, Object> typed(String type, boolean nullable) {
    return ordered("type", nullable ? List.of(type, "null") : type);
  }

  private static Map<String, Object> ordered(String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }

  private static List<Object> enumValues(EnumSchema schema) {
    List<Object> values = new ArrayList<>();
    for (String value : schema.values()) {
      values.add(literal(schema.type(), value));
    }
    if (schema.nullable()) {
      values.add(null);
    }
    return values;
  }

  /**
   * An enum member as a JSON value of the enum's type, so integer and number enums stay numeric.
   */
  private static Object literal(PrimitiveType type, String value) {
    return switch (type) {
      case INTEGER -> INTEGER.matcher(value).matches() ? new BigInteger(value) : value;
      case NUMBER -> NUMBER.matcher(value).matches() ? new BigDecimal(value) : value;
      case BOOLEAN ->
          value.equals("true") || value.equals("false") ? Boolean.valueOf(value) : value;
      case STRING -> value;
    };
  }

  private static String typeName(PrimitiveType type) {
    return switch (type) {
      case STRING -> "string";
      case INTEGER -> "integer";
      case NUMBER -> "number";
      case BOOLEAN -> "boolean";
    };
  }
}
