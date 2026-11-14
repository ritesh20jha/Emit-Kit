package dev.emitkit.gen.javasdk;

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
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Java types for an API's schemas, and the records, enums and sealed interfaces that model them.
 *
 * <p>An inline object or enum is a nested type of the declaration that owns it, named {@code
 * {Owner}{Property}}: {@code Order.shipping} becomes {@code OrderShipping}. A named schema that is
 * only an alias (a primitive, an array, a bare ref) gets no file; its uses are inlined. A oneOf
 * with a discriminator and only named object options is a sealed interface; any other oneOf is
 * {@code JsonNode}. Output follows document order, so it is byte-stable.
 */
final class JavaTypeRenderer {

  static final String JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";
  static final String JSON_VALUE = "com.fasterxml.jackson.annotation.JsonValue";
  static final String JSON_TYPE_INFO = "com.fasterxml.jackson.annotation.JsonTypeInfo";
  static final String JSON_SUB_TYPES = "com.fasterxml.jackson.annotation.JsonSubTypes";
  static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode";
  static final String LIST = "java.util.List";
  static final String MAP = "java.util.Map";

  private static final String INDENT = "  ";

  /** A top-level type: its simple name, the imports it needs, and its full declaration. */
  record TypeFile(String name, Set<String> imports, String body) {}

  /** Properties of an object schema, with allOf parts merged in. */
  private record Props(Map<String, Schema> properties, Set<String> required) {}

  /**
   * Where a declaration's nested types go. {@code indent} is the owner's indentation. The imports
   * are shared by the whole file, and {@code resource} files import model types from {@code
   * model/}.
   */
  static final class Scope {
    final String indent;
    final boolean resource;
    final Set<String> imports;
    final List<String> nested = new ArrayList<>();

    Scope(String indent, boolean resource, Set<String> imports) {
      this.indent = indent;
      this.resource = resource;
      this.imports = imports;
    }

    /** A scope for a type nested one level inside this one. */
    Scope nestedScope() {
      return new Scope(indent + INDENT, resource, imports);
    }
  }

  private final Map<String, Schema> schemas;
  private final String modelPackage;
  private final NameStrategy java = NameStrategy.forLanguage(Language.JAVA);

  /** Schema name to the sealed interfaces that list it as an option. */
  private final Map<String, List<String>> sealedParents = new HashMap<>();

  JavaTypeRenderer(ApiModel model, String basePackage) {
    this.schemas = model.schemas();
    this.modelPackage = basePackage + ".model";
    for (var entry : schemas.entrySet()) {
      if (entry.getValue() instanceof OneOfSchema union && isSealed(union)) {
        for (Schema option : union.options()) {
          String parent = java.type(entry.getKey());
          sealedParents
              .computeIfAbsent(((RefSchema) option).name(), k -> new ArrayList<>())
              .add(parent);
        }
      }
    }
  }

  /** Every top-level type of the API, in schema order. Aliases produce no file. */
  List<TypeFile> files() {
    List<TypeFile> files = new ArrayList<>();
    Map<String, String> rawByName = new HashMap<>();
    for (var entry : schemas.entrySet()) {
      Schema schema = entry.getValue();
      String name = java.type(entry.getKey());
      Set<String> imports = new TreeSet<>();
      Scope top = new Scope("", false, imports);
      String body;
      if (schema instanceof EnumSchema e) {
        body = enumText(top, name, e);
      } else if (schema instanceof OneOfSchema u && isSealed(u)) {
        body = sealedText(top, name, u);
      } else if (isRecord(schema)) {
        List<String> components = components(propsOf(schema), name, top, true);
        String implementsClause = implementsClause(entry.getKey());
        body = record(top, name, schema.description(), components, implementsClause);
      } else {
        continue;
      }
      String previous = rawByName.putIfAbsent(name, entry.getKey());
      if (previous != null) {
        throw new IllegalArgumentException(
            "schemas "
                + previous
                + " and "
                + entry.getKey()
                + " both become the Java type "
                + name);
      }
      files.add(new TypeFile(name, imports, body));
    }
    return files;
  }

  /** Package of the generated model types, for imports from the resources package. */
  String modelPackage() {
    return modelPackage;
  }

  /**
   * The Java type of {@code schema}. A scalar is primitive only when {@code required} and not
   * nullable; every other type is a reference. An inline object or enum is declared in {@code
   * scope} under {@code hint}, and when there is no hint it falls back to a map or a scalar.
   */
  String type(Schema schema, boolean required, String hint, Scope scope) {
    boolean primitive = required && !schema.nullable();
    return switch (schema) {
      case PrimitiveSchema p -> scalar(p.type(), p.format(), primitive);
      case EnumSchema e -> {
        if (hint == null) {
          yield scalar(e.type(), null, primitive);
        }
        scope.nested.add(enumText(scope.nestedScope(), hint, e));
        yield hint;
      }
      case RefSchema r -> reference(r, required, scope);
      case ArraySchema a -> {
        scope.imports.add(LIST);
        yield "List<" + type(a.items(), false, hint, scope) + ">";
      }
      case ObjectSchema o -> objectType(o, hint, scope);
      case AllOfSchema a -> objectType(a, hint, scope);
      case OneOfSchema u -> {
        scope.imports.add(JSON_NODE);
        yield "JsonNode";
      }
    };
  }

  /** The Java type a $ref names, which is a declared type when the target has one. */
  String reference(RefSchema r, boolean required, Scope scope) {
    Schema target = target(r.name());
    if (isNamed(target)) {
      if (scope.resource) {
        scope.imports.add(modelPackage + "." + java.type(r.name()));
      }
      return java.type(r.name());
    }
    if (target instanceof OneOfSchema) {
      scope.imports.add(JSON_NODE);
      return "JsonNode";
    }
    return type(target, required && !r.nullable(), null, scope);
  }

  /** The schema a $ref names. */
  Schema target(String name) {
    Schema schema = schemas.get(name);
    if (schema == null) {
      throw new IllegalArgumentException("no schema named " + name);
    }
    return schema;
  }

  /** Record components, one per property, with the Jackson name when {@code json} is set. */
  private List<String> components(Props props, String ownerName, Scope scope, boolean json) {
    List<String> out = new ArrayList<>();
    Map<String, String> rawByMember = new HashMap<>();
    for (var entry : props.properties().entrySet()) {
      String raw = entry.getKey();
      boolean required = props.required().contains(raw);
      String type = type(entry.getValue(), required, ownerName + java.type(raw), scope);
      String member = java.member(raw);
      String previous = rawByMember.putIfAbsent(member, raw);
      if (previous != null) {
        throw new IllegalArgumentException(
            "properties "
                + previous
                + " and "
                + raw
                + " of "
                + ownerName
                + " both become the record component "
                + member);
      }
      if (json) {
        scope.imports.add(JSON_PROPERTY);
        out.add("@JsonProperty(" + JavaClientView.quote(raw) + ") " + type + " " + member);
      } else {
        out.add(type + " " + member);
      }
    }
    return out;
  }

  /** The properties of an object or allOf schema, with the parts of an allOf merged in. */
  private Props propsOf(Schema schema) {
    Map<String, Schema> properties = new LinkedHashMap<>();
    Set<String> required = new LinkedHashSet<>();
    collect(schema, properties, required);
    return new Props(properties, required);
  }

  private void collect(Schema schema, Map<String, Schema> properties, Set<String> required) {
    switch (schema) {
      case ObjectSchema o -> {
        properties.putAll(o.properties());
        required.addAll(o.required());
      }
      case AllOfSchema a -> a.parts().forEach(part -> collect(part, properties, required));
      case RefSchema r -> collect(target(r.name()), properties, required);
      default -> {}
    }
  }

  /** Whether a schema becomes a record: an object or allOf with at least one property. */
  boolean isRecord(Schema schema) {
    return (schema instanceof ObjectSchema || schema instanceof AllOfSchema)
        && !propsOf(schema).properties().isEmpty();
  }

  /** Whether a schema is declared as a type of its own, rather than inlined. */
  boolean isNamed(Schema schema) {
    return isRecord(schema)
        || schema instanceof EnumSchema
        || (schema instanceof OneOfSchema u && isSealed(u));
  }

  /** A discriminated oneOf whose options are all refs to records. */
  private boolean isSealed(OneOfSchema union) {
    return union.discriminator() != null
        && !union.options().isEmpty()
        && union.options().stream()
            .allMatch(o -> o instanceof RefSchema r && isRecord(target(r.name())));
  }

  private String objectType(Schema schema, String hint, Scope scope) {
    if (hint == null || !isRecord(schema)) {
      scope.imports.add(MAP);
      Schema extra = schema instanceof ObjectSchema o ? o.additionalProperties() : null;
      return "Map<String, " + (extra == null ? "Object" : type(extra, false, hint, scope)) + ">";
    }
    Scope child = scope.nestedScope();
    List<String> components = components(propsOf(schema), hint, child, true);
    scope.nested.add(record(child, hint, schema.description(), components, ""));
    return hint;
  }

  private String implementsClause(String rawName) {
    List<String> parents = sealedParents.getOrDefault(rawName, List.of());
    return parents.isEmpty() ? "" : " implements " + String.join(", ", parents);
  }

  /**
   * A record declaration at the scope's indentation. Its nested types, already rendered in the
   * scope, follow the components.
   */
  String record(
      Scope scope,
      String name,
      String description,
      List<String> components,
      String implementsClause) {
    String indent = scope.indent;
    StringBuilder out = new StringBuilder();
    doc(out, indent, description);
    out.append(indent).append("public record ").append(name).append('(');
    if (!components.isEmpty()) {
      out.append('\n');
      for (int i = 0; i < components.size(); i++) {
        out.append(indent).append(INDENT).append(INDENT).append(components.get(i));
        out.append(i < components.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent);
    }
    out.append(')').append(implementsClause);
    if (scope.nested.isEmpty()) {
      return out.append(" {}\n").toString();
    }
    out.append(" {\n");
    for (String nested : scope.nested) {
      out.append('\n').append(nested);
    }
    return out.append(indent).append("}\n").toString();
  }

  private String enumText(Scope scope, String name, EnumSchema e) {
    if (e.values().isEmpty()) {
      throw new IllegalArgumentException("enum " + name + " has no values");
    }
    scope.imports.add(JSON_VALUE);
    String indent = scope.indent;
    String valueType = scalar(e.type(), null, true);
    StringBuilder out = new StringBuilder();
    doc(out, indent, e.description());
    out.append(indent).append("public enum ").append(name).append(" {\n");
    Set<String> constants = new HashSet<>();
    for (int i = 0; i < e.values().size(); i++) {
      String raw = e.values().get(i);
      String constant = constantName(raw);
      if (!constants.add(constant)) {
        throw new IllegalArgumentException(
            "enum " + name + " has values that map to the same constant " + constant);
      }
      out.append(indent).append(INDENT).append(constant);
      out.append('(').append(literal(e.type(), raw)).append(')');
      out.append(i < e.values().size() - 1 ? ",\n" : ";\n");
    }
    out.append('\n');
    out.append(indent)
        .append(INDENT)
        .append("private final ")
        .append(valueType)
        .append(" value;\n");
    out.append('\n');
    out.append(indent).append(INDENT).append(name);
    out.append('(').append(valueType).append(" value) {\n");
    out.append(indent).append(INDENT).append(INDENT).append("this.value = value;\n");
    out.append(indent).append(INDENT).append("}\n\n");
    out.append(indent).append(INDENT).append("@JsonValue\n");
    out.append(indent).append(INDENT).append("public ").append(valueType).append(" value() {\n");
    out.append(indent).append(INDENT).append(INDENT).append("return value;\n");
    out.append(indent).append(INDENT).append("}\n");
    return out.append(indent).append("}\n").toString();
  }

  private String sealedText(Scope scope, String name, OneOfSchema union) {
    scope.imports.add(JSON_TYPE_INFO);
    scope.imports.add(JSON_SUB_TYPES);
    String indent = scope.indent;
    StringBuilder out = new StringBuilder();
    doc(out, indent, union.description());
    String args = indent + INDENT + INDENT;
    out.append(indent).append("@JsonTypeInfo(\n");
    out.append(args).append("use = JsonTypeInfo.Id.NAME,\n");
    out.append(args).append("include = JsonTypeInfo.As.EXISTING_PROPERTY,\n");
    out.append(args).append("property = ").append(JavaClientView.quote(union.discriminator()));
    out.append(",\n").append(args).append("visible = true)\n");
    out.append(indent).append("@JsonSubTypes({\n");
    List<String> permits = new ArrayList<>();
    for (int i = 0; i < union.options().size(); i++) {
      RefSchema option = (RefSchema) union.options().get(i);
      String type = java.type(option.name());
      permits.add(type);
      // TODO(spec-engine): OneOfSchema drops discriminator mapping; subtype names fall back to
      // schema names.
      out.append(indent).append(INDENT).append("@JsonSubTypes.Type(value = ").append(type);
      out.append(".class, name = ").append(JavaClientView.quote(option.name())).append(')');
      out.append(i < union.options().size() - 1 ? ",\n" : "\n");
    }
    out.append(indent).append("})\n");
    String permitList = String.join(", ", permits);
    out.append(indent).append("public sealed interface ").append(name);
    return out.append(" permits ").append(permitList).append(" {}\n").toString();
  }

  private static void doc(StringBuilder out, String indent, String description) {
    if (description != null && !description.isBlank()) {
      out.append(indent).append("/** ").append(JavaClientView.docText(description)).append(" */\n");
    }
  }

  /** Java type for a scalar. Boxed unless {@code primitive}, which {@code int} and so on are. */
  static String scalar(PrimitiveType type, String format, boolean primitive) {
    return switch (type) {
      case STRING -> "String";
      case INTEGER ->
          "int64".equals(format) ? (primitive ? "long" : "Long") : (primitive ? "int" : "Integer");
      case NUMBER ->
          "float".equals(format)
              ? (primitive ? "float" : "Float")
              : (primitive ? "double" : "Double");
      case BOOLEAN -> primitive ? "boolean" : "Boolean";
    };
  }

  /** A Java constant name for an enum value: upper case, separators as underscores. */
  static String constantName(String raw) {
    String name =
        raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (name.isEmpty()) {
      return "VALUE";
    }
    return Character.isDigit(name.charAt(0)) ? "_" + name : name;
  }

  /** The value of an enum constant as a Java literal of the enum's type. */
  private static String literal(PrimitiveType type, String raw) {
    return switch (type) {
      case STRING -> JavaClientView.quote(raw);
      case INTEGER -> String.valueOf(Integer.parseInt(raw));
      case NUMBER -> Double.toString(Double.parseDouble(raw));
      case BOOLEAN -> {
        if (!"true".equals(raw) && !"false".equals(raw)) {
          throw new IllegalArgumentException("boolean enum value is not true or false: " + raw);
        }
        yield raw;
      }
    };
  }

  /** Joins a set of imports into the import block of a file, with a blank line after it. */
  static String importBlock(Set<String> imports) {
    if (imports.isEmpty()) {
      return "";
    }
    return imports.stream().map(i -> "import " + i + ";\n").collect(Collectors.joining()) + "\n";
  }
}
