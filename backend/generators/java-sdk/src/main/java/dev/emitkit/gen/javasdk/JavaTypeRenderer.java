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
 * Java types for an API's schemas. An inline object or enum is nested in its owner, named {@code
 * {Owner}{Property}}. Aliases (primitives, arrays, bare refs) get no file. A discriminated oneOf of
 * records is a sealed interface; any other oneOf is {@code JsonNode}.
 */
final class JavaTypeRenderer {

  static final String JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";
  static final String JSON_VALUE = "com.fasterxml.jackson.annotation.JsonValue";
  static final String JSON_TYPE_INFO = "com.fasterxml.jackson.annotation.JsonTypeInfo";
  static final String JSON_SUB_TYPES = "com.fasterxml.jackson.annotation.JsonSubTypes";
  static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode";

  static final String LIST = "java.util.List";
  static final String MAP = "java.util.Map";

  /** Methods every Java object has. A record component or operation with one gets a _. */
  static final Set<String> OBJECT_METHODS =
      Set.of(
          "clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");

  private static final String INDENT = "  ";

  /** A top-level type: its simple name, the imports it needs, and its full declaration. */
  record TypeFile(String name, Set<String> imports, String body) {}

  /** Properties of an object schema, with allOf parts merged in. */
  private record Props(Map<String, Schema> properties, Set<String> required) {}

  /** Nested types of one owner, at its indentation. Imports are shared by the whole file. */
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
      claim(rawByName, name, entry.getKey(), "schemas");
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
      if (OBJECT_METHODS.contains(member)) {
        member = member + "_";
      }
      claim(rawByMember, member, raw, "properties of " + ownerName);
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
      Schema extra = schema instanceof ObjectSchema o ? o.additionalProperties() : null;
      scope.imports.add(MAP);
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
    String header = indent + "public record " + name + "(";
    if (!components.isEmpty()) {
      header +=
          "\n"
              + String.join(
                  ",\n", components.stream().map(c -> indent + INDENT + INDENT + c).toList())
              + "\n"
              + indent;
    }
    String tail =
        scope.nested.isEmpty()
            ? " {}\n"
            : " {\n"
                + String.join("", scope.nested.stream().map(n -> "\n" + n).toList())
                + indent
                + "}\n";
    return indented(doc(description), indent) + header + ")" + implementsClause + tail;
  }

  private String enumText(Scope scope, String name, EnumSchema e) {
    scope.imports.add(JSON_VALUE);
    List<String> constants = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String raw : e.values()) {
      String constant = constantName(raw);
      if (!seen.add(constant)) {
        throw new IllegalArgumentException(
            "enum %s has values that map to the same constant %s".formatted(name, constant));
      }
      constants.add(constant + "(" + literal(e.type(), raw) + ")");
    }
    String valueType = scalar(e.type(), null, true);
    String body =
        """
        public enum %s {
          %s;

          private final %s value;

          %s(%s value) {
            this.value = value;
          }

          @JsonValue
          public %s value() {
            return value;
          }
        }
        """
            .formatted(
                name, String.join(",\n  ", constants), valueType, name, valueType, valueType);
    return indented(doc(e.description()) + body, scope.indent);
  }

  private String sealedText(Scope scope, String name, OneOfSchema union) {
    scope.imports.add(JSON_TYPE_INFO);
    scope.imports.add(JSON_SUB_TYPES);
    List<String> subtypes = new ArrayList<>();
    List<String> permits = new ArrayList<>();
    for (Schema option : union.options()) {
      String raw = ((RefSchema) option).name();
      String type = java.type(raw);
      permits.add(type);
      // TODO(spec-engine): OneOfSchema drops discriminator mapping; subtype names fall back to
      // schema names.
      subtypes.add(
          "@JsonSubTypes.Type(value = "
              + type
              + ".class, name = "
              + JavaClientView.quote(raw)
              + ")");
    }
    String body =
        """
        @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = %s,
            visible = true)
        @JsonSubTypes({
          %s
        })
        public sealed interface %s permits %s {}
        """
            .formatted(
                JavaClientView.quote(union.discriminator()),
                String.join(",\n  ", subtypes),
                name,
                String.join(", ", permits));
    return indented(doc(union.description()) + body, scope.indent);
  }

  /** Claims {@code name} for {@code raw}. Fails when another original name already has it. */
  static void claim(Map<String, String> claims, String name, String raw, String what) {
    String previous = claims.putIfAbsent(name, raw);
    if (previous != null) {
      throw new IllegalArgumentException(
          "%s %s and %s both become %s".formatted(what, previous, raw, name));
    }
  }

  /** A javadoc line for {@code description}, or nothing when there is none. */
  private static String doc(String description) {
    if (description == null || description.isBlank()) {
      return "";
    }
    return "/** " + JavaClientView.docText(description) + " */\n";
  }

  /** Indents every non-empty line of {@code text} by {@code indent}. */
  private static String indented(String text, String indent) {
    if (text.isEmpty()) {
      return "";
    }
    return text.lines()
        .map(line -> line.isEmpty() ? line : indent + line)
        .collect(Collectors.joining("\n", "", "\n"));
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
