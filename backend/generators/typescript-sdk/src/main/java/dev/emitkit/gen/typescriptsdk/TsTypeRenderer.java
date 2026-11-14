package dev.emitkit.gen.typescriptsdk;

import static java.util.stream.Collectors.joining;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Renders schemas as TypeScript type text. Named schemas are referenced by their {@link TsNames}
 * name and recorded, so a caller can import exactly the names a file uses.
 */
final class TsTypeRenderer {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

  private final TsNames names;
  private final Set<String> referenced = new TreeSet<>();

  TsTypeRenderer(TsNames names) {
    this.names = names;
  }

  /** The inline type of {@code schema}, with {@code | null} when it is nullable. */
  String type(Schema schema) {
    String type =
        switch (schema) {
          case ObjectSchema object -> object(object);
          case ArraySchema array -> wrap(type(array.items())) + "[]";
          case PrimitiveSchema primitive -> primitive(primitive.type());
          case EnumSchema enumSchema -> enumType(enumSchema);
          case RefSchema ref -> reference(ref.name());
          case OneOfSchema oneOf ->
              oneOf.options().isEmpty()
                  ? "never"
                  : oneOf.options().stream().map(this::type).collect(joining(" | "));
          case AllOfSchema allOf ->
              allOf.parts().isEmpty()
                  ? "unknown"
                  : allOf.parts().stream().map(p -> wrap(type(p))).collect(joining(" & "));
        };
    return schema.nullable() ? type + " | null" : type;
  }

  /**
   * An exported declaration for the named schema: an {@code interface} for a plain object, else a
   * {@code type} alias. Doc comments come from the schema descriptions.
   */
  String declaration(String name, Schema schema) {
    StringBuilder out = new StringBuilder();
    doc(out, "", schema.description());
    if (schema instanceof ObjectSchema object && isInterface(object)) {
      out.append("export interface ").append(name).append(" {\n");
      for (var entry : object.properties().entrySet()) {
        Schema property = entry.getValue();
        doc(out, "  ", property.description());
        out.append("  ")
            .append(propertyKey(entry.getKey()))
            .append(object.required().contains(entry.getKey()) ? "" : "?")
            .append(": ")
            .append(type(property))
            .append(";\n");
      }
      return out.append("}\n").toString();
    }
    return out.append("export type ")
        .append(name)
        .append(" = ")
        .append(type(schema))
        .append(";\n")
        .toString();
  }

  /** Every name {@link #type} has rendered since the last call, sorted, then cleared. */
  Set<String> takeReferenced() {
    Set<String> names = new TreeSet<>(referenced);
    referenced.clear();
    return names;
  }

  /** A property name as a TypeScript key: bare when it is an identifier, else quoted. */
  static String propertyKey(String raw) {
    return isIdentifier(raw) ? raw : TsClientView.quote(raw);
  }

  /** Whether {@code raw} can be written as a dotted property name. */
  static boolean isIdentifier(String raw) {
    return IDENTIFIER.matcher(raw).matches();
  }

  /** Whether {@code object} renders as an interface: it has properties and no value type. */
  static boolean isInterface(ObjectSchema object) {
    return !object.nullable()
        && !object.properties().isEmpty()
        && object.additionalProperties() == null;
  }

  static String declarations(ApiModel model, TsNames names) {
    TsTypeRenderer renderer = new TsTypeRenderer(names);
    StringBuilder out = new StringBuilder();
    for (var entry : model.schemas().entrySet()) {
      out.append('\n')
          .append(renderer.declaration(names.typeName(entry.getKey()), entry.getValue()));
    }
    return out.toString();
  }

  private String object(ObjectSchema object) {
    if (object.properties().isEmpty()) {
      return object.additionalProperties() == null
          ? "Record<string, unknown>"
          : "Record<string, " + type(object.additionalProperties()) + ">";
    }
    String fields =
        object.properties().entrySet().stream()
            .map(e ->
                propertyKey(e.getKey())
                    + (object.required().contains(e.getKey()) ? "" : "?")
                    + ": "
                    + type(e.getValue()))
            .collect(joining("; ", "{ ", " }"));
    return object.additionalProperties() == null
        ? fields
        : fields + " & Record<string, " + type(object.additionalProperties()) + ">";
  }

  private String reference(String raw) {
    String name = names.typeName(raw);
    referenced.add(name);
    return name;
  }

  private static String primitive(PrimitiveType type) {
    return switch (type) {
      case STRING -> "string";
      case BOOLEAN -> "boolean";
      case INTEGER, NUMBER -> "number";
    };
  }

  private static String enumType(EnumSchema schema) {
    if (schema.values().isEmpty()) {
      return "never";
    }
    return schema.values().stream().map(v -> literal(schema.type(), v)).collect(joining(" | "));
  }

  /** A value as a TypeScript literal type: numbers and booleans bare, everything else quoted. */
  private static String literal(PrimitiveType type, String value) {
    boolean bare =
        switch (type) {
          case INTEGER, NUMBER -> NUMBER.matcher(value).matches();
          case BOOLEAN -> value.equals("true") || value.equals("false");
          case STRING -> false;
        };
    return bare ? value : TsClientView.quote(value);
  }

  /** Parenthesises a union or intersection so it binds correctly as an array item or part. */
  private static String wrap(String type) {
    return type.contains(" | ") || type.contains(" & ") ? "(" + type + ")" : type;
  }

  /** A single-line JSDoc, only when there is a description. */
  private static void doc(StringBuilder out, String indent, String description) {
    if (description != null && !TsClientView.docText(description).isEmpty()) {
      out.append(indent).append("/** ").append(TsClientView.docText(description)).append(" */\n");
    }
  }
}
