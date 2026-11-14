package dev.emitkit.gen.pythonsdk;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Maps the sealed {@link Schema} model to Python type expressions and to the module-level
 * definitions they name: a Pydantic model per object schema, an {@code Enum} per enum, and a type
 * alias for every other named schema. Inline objects and enums are named from the property or
 * operation that holds them.
 *
 * <p>Definitions are discovered first, then emitted in dependency order. A reference to a
 * definition not yet emitted (a cycle) is written as a quoted forward reference, and that model is
 * rebuilt at the end of the module.
 */
final class PyTypeRenderer {

  /** Names the generated modules import, so no definition may take one. */
  private static final Set<String> IMPORTED =
      Set.of(
          "Annotated",
          "Any",
          "BaseModel",
          "ConfigDict",
          "Enum",
          "Field",
          "Optional",
          "TypeAdapter",
          "Union");

  /** Lower-case names used in annotations; a field of one of these would shadow it. */
  private static final Set<String> FIELD_SHADOWS =
      Set.of("bool", "dict", "float", "int", "list", "model_config", "str");

  private static final Set<String> TYPING = Set.of("Annotated", "Any", "Optional", "Union");
  private static final Set<String> PYDANTIC = Set.of("BaseModel", "ConfigDict", "Field");
  private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9]\\d*)");
  private static final Pattern NUMBER =
      Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d+)?");

  /** The models module: whether it has imports, the import lines and the body after them. */
  record Module(boolean hasImports, String imports, String body) {}

  private enum Kind {
    MODEL,
    ENUM,
    ALIAS
  }

  /** A module-level name, the schema it renders and the hint it was named from. */
  private static final class Definition {
    final String name;
    final Kind kind;
    final Schema schema;
    final String hint;
    final Set<String> deps = new LinkedHashSet<>();

    Definition(String name, Kind kind, Schema schema, String hint) {
      this.name = name;
      this.kind = kind;
      this.schema = schema;
      this.hint = hint;
    }
  }

  /**
   * What a type expression may refer to: the definitions already emitted (null when all are), the
   * names it uses and the imports it needs.
   */
  static final class Scope {
    final Set<String> emitted;
    final Set<String> imports;
    final Set<String> names = new LinkedHashSet<>();
    boolean forwardRef;

    private Scope(Set<String> emitted, Set<String> imports) {
      this.emitted = emitted;
      this.imports = imports;
    }

    /** A scope for discovering definitions, where nothing is emitted yet. */
    static Scope discovery() {
      return new Scope(Set.of(), new TreeSet<>());
    }

    /** A scope for code outside the models module, where every definition is importable. */
    static Scope complete(Set<String> imports) {
      return new Scope(null, imports);
    }

    String use(String name) {
      imports.add(name);
      return name;
    }
  }

  private final ApiModel model;
  private final NameStrategy names = NameStrategy.forLanguage(Language.PYTHON);
  private final Set<String> taken = new HashSet<>(IMPORTED);
  private final Map<String, Definition> components = new HashMap<>();
  private final Map<Schema, Definition> inline = new HashMap<>();
  private final List<Definition> defs = new ArrayList<>();

  PyTypeRenderer(ApiModel model) {
    this.model = model;
    for (var entry : model.schemas().entrySet()) {
      String key = entry.getKey();
      components.put(key, define(unique(names.type(key), taken), key, entry.getValue()));
    }
    for (Operation op : model.operations()) {
      for (var parameter : op.parameters()) {
        typeOf(parameter.schema(), hint(op, parameter.name()), Scope.discovery());
      }
      if (op.requestBody() != null) {
        typeOf(op.requestBody().schema(), hint(op, "request"), Scope.discovery());
      }
      Schema response = successSchema(op);
      if (response != null) {
        typeOf(response, hint(op, "response"), Scope.discovery());
      }
    }
    // Rendering can add definitions for inline objects it finds, so the list is read by index.
    for (int i = 0; i < defs.size(); i++) {
      Definition d = defs.get(i);
      Scope scope = Scope.discovery();
      render(d, scope);
      d.deps.addAll(scope.names);
    }
  }

  /** The hint naming an inline schema of an operation: its operation id and a part name. */
  static String hint(Operation op, String part) {
    return op.operationId() + " " + part;
  }

  /** The first 2xx response that has a schema, or null when none does. */
  static Schema successSchema(Operation op) {
    for (Response response : op.responses().values()) {
      if (response.status().startsWith("2") && response.schema() != null) {
        return response.schema();
      }
    }
    return null;
  }

  /** A type expression for code outside the models module. */
  String typeOf(Schema schema, String hint, Scope scope) {
    return switch (schema) {
      case RefSchema r -> nullable(reference(component(r.name()), scope), r.nullable(), scope);
      case PrimitiveSchema p -> nullable(primitive(p.type()), p.nullable(), scope);
      case ArraySchema a ->
          nullable("list[" + typeOf(a.items(), hint + " item", scope) + "]", a.nullable(), scope);
      case EnumSchema e -> nullable(reference(inline(e, hint), scope), e.nullable(), scope);
      case OneOfSchema o -> nullable(union(o, hint, scope), o.nullable(), scope);
      case AllOfSchema a -> nullable(allOf(a, hint, scope), a.nullable(), scope);
      case ObjectSchema o ->
          nullable(
              o.properties().isEmpty() ? dict(o, hint, scope) : reference(inline(o, hint), scope),
              o.nullable(),
              scope);
    };
  }

  /** The models module, with definitions in dependency order. */
  Module modelsModule() {
    Set<String> imports = new TreeSet<>();
    Set<String> emitted = new HashSet<>();
    List<String> blocks = new ArrayList<>();
    List<String> rebuilds = new ArrayList<>();
    for (Definition d : ordered()) {
      Scope scope = new Scope(emitted, imports);
      blocks.add(render(d, scope));
      if (scope.forwardRef && d.kind == Kind.MODEL) {
        rebuilds.add(d.name + ".model_rebuild()");
      }
      emitted.add(d.name);
    }
    String body = String.join("\n\n\n", blocks);
    if (!rebuilds.isEmpty()) {
      body += "\n\n\n" + String.join("\n", rebuilds);
    }
    String imported = importLines(imports);
    // The body carries its own final newline, so an empty module is only the header comment.
    return new Module(!imported.isEmpty(), imported, body.isEmpty() ? "" : body + "\n");
  }

  /** Wraps {@code text} in Optional when the schema is nullable. */
  static String nullable(String text, boolean nullable, Scope scope) {
    return nullable ? wrapOptional(text, scope) : text;
  }

  static String wrapOptional(String text, Scope scope) {
    return scope.use("Optional") + "[" + text + "]";
  }

  private Definition component(String name) {
    Definition d = components.get(name);
    if (d == null) {
      throw new IllegalStateException("reference to unknown schema " + name);
    }
    return d;
  }

  private Definition inline(Schema key, String hint) {
    return inline.computeIfAbsent(key, k -> define(unique(names.type(hint), taken), hint, k));
  }

  /** Creates the definition for a named or inline schema. Object-like schemas become models. */
  private Definition define(String name, String hint, Schema schema) {
    Kind kind = Kind.ALIAS;
    Schema shape = schema;
    if (schema instanceof EnumSchema) {
      kind = Kind.ENUM;
    } else if (schema instanceof ObjectSchema o && !o.properties().isEmpty()) {
      kind = Kind.MODEL;
    } else if (schema instanceof AllOfSchema all) {
      ObjectSchema merged = mergeParts(all, new HashSet<>());
      if (merged != null && !merged.properties().isEmpty()) {
        kind = Kind.MODEL;
        shape = merged;
      }
    }
    Definition d = new Definition(name, kind, shape, hint);
    defs.add(d);
    return d;
  }

  private static String reference(Definition d, Scope scope) {
    scope.names.add(d.name);
    if (scope.emitted == null || scope.emitted.contains(d.name)) {
      return d.name;
    }
    scope.forwardRef = true;
    return PyClientView.quote(d.name);
  }

  private String union(OneOfSchema o, String hint, Scope scope) {
    List<String> options = new ArrayList<>();
    for (int i = 0; i < o.options().size(); i++) {
      options.add(typeOf(o.options().get(i), hint + " option " + (i + 1), scope));
    }
    String union = scope.use("Union") + "[" + String.join(", ", options) + "]";
    if (o.discriminator() == null) {
      return union;
    }
    return scope.use("Annotated")
        + "["
        + union
        + ", "
        + scope.use("Field")
        + "(discriminator="
        + PyClientView.quote(o.discriminator())
        + ")]";
  }

  private String allOf(AllOfSchema all, String hint, Scope scope) {
    ObjectSchema merged = mergeParts(all, new HashSet<>());
    if (merged != null && !merged.properties().isEmpty()) {
      return reference(inline(all, hint), scope);
    }
    return typeOf(all.parts().get(0), hint, scope);
  }

  private String dict(ObjectSchema o, String hint, Scope scope) {
    String value =
        o.additionalProperties() == null
            ? scope.use("Any")
            : typeOf(o.additionalProperties(), hint + " value", scope);
    return "dict[str, " + value + "]";
  }

  private static String primitive(PrimitiveType type) {
    return switch (type) {
      case STRING -> "str";
      case INTEGER -> "int";
      case NUMBER -> "float";
      case BOOLEAN -> "bool";
    };
  }

  /**
   * The properties and required names of an allOf, flattened into one object. Null when no part is
   * object-like. Pointers are followed through the raw schemas, so the order of definitions does
   * not matter.
   */
  private ObjectSchema mergeParts(AllOfSchema all, Set<String> seen) {
    Map<String, Schema> properties = new LinkedHashMap<>();
    Set<String> required = new LinkedHashSet<>();
    boolean found = false;
    for (Schema part : all.parts()) {
      ObjectSchema o = objectOf(part, seen);
      if (o == null) {
        continue;
      }
      found = true;
      o.properties().forEach(properties::putIfAbsent);
      required.addAll(o.required());
    }
    return found ? new ObjectSchema(properties, required, null, false, null) : null;
  }

  private ObjectSchema objectOf(Schema part, Set<String> seen) {
    return switch (part) {
      case ObjectSchema o -> o;
      case AllOfSchema a -> mergeParts(a, seen);
      case RefSchema r -> {
        Schema target = model.schemas().get(r.name());
        if (target == null || !seen.add(r.name())) {
          yield null;
        }
        ObjectSchema o = objectOf(target, seen);
        seen.remove(r.name());
        yield o;
      }
      default -> null;
    };
  }

  private String render(Definition d, Scope scope) {
    return switch (d.kind) {
      case MODEL -> model(d, (ObjectSchema) d.schema, scope);
      case ENUM -> enumClass(d, (EnumSchema) d.schema, scope);
      case ALIAS -> d.name + " = " + typeOf(d.schema, d.hint, scope);
    };
  }

  private String model(Definition d, ObjectSchema o, Scope scope) {
    scope.use("BaseModel");
    scope.use("ConfigDict");
    List<String> lines = new ArrayList<>();
    lines.add("class " + d.name + "(BaseModel):");
    lines.add("    model_config = ConfigDict(populate_by_name=True)");
    if (!o.properties().isEmpty()) {
      lines.add("");
    }
    Set<String> used = new HashSet<>();
    for (var property : o.properties().entrySet()) {
      lines.add(field(d, o, property.getKey(), property.getValue(), used, scope));
    }
    return String.join("\n", lines);
  }

  /** One field line. A name that differs from the spec name gets an alias. */
  private String field(
      Definition owner, ObjectSchema o, String raw, Schema schema, Set<String> used, Scope scope) {
    String base = names.member(raw);
    // Pydantic treats a leading underscore as a private attribute, so such a field takes a prefix.
    if (base.startsWith("_")) {
      base = "field" + base;
    } else if (FIELD_SHADOWS.contains(base)) {
      base += "_";
    }
    String py = unique(base, used);
    boolean required = o.required().contains(raw);
    String type = typeOf(schema, owner.hint + " " + raw, scope);
    if (!required && !schema.nullable()) {
      type = wrapOptional(type, scope);
    }
    String value = null;
    if (!py.equals(raw)) {
      value =
          scope.use("Field")
              + "("
              + (required ? "" : "default=None, ")
              + "alias="
              + PyClientView.quote(raw)
              + ")";
    } else if (!required) {
      value = "None";
    }
    return "    " + py + ": " + type + (value == null ? "" : " = " + value);
  }

  private String enumClass(Definition d, EnumSchema e, Scope scope) {
    scope.use("Enum");
    String base = enumBase(e);
    List<String> lines = new ArrayList<>();
    lines.add("class " + d.name + "(" + (base.isEmpty() ? "" : base + ", ") + "Enum):");
    Set<String> used = new HashSet<>();
    for (String value : e.values()) {
      String member = unique(names.member(value).toUpperCase(Locale.ROOT), used);
      lines.add("    " + member + " = " + enumLiteral(base, value));
    }
    if (e.values().isEmpty()) {
      lines.add("    pass");
    }
    return String.join("\n", lines);
  }

  /**
   * The mixin base of an enum: {@code int} or {@code float} when every value is a number of that
   * kind, {@code str} otherwise, and none (a plain Enum) for booleans.
   */
  private static String enumBase(EnumSchema e) {
    return switch (e.type()) {
      case STRING -> "str";
      case INTEGER -> allMatch(e.values(), INTEGER) ? "int" : "str";
      case NUMBER -> allMatch(e.values(), NUMBER) ? "float" : "str";
      case BOOLEAN ->
          e.values().stream().allMatch(v -> v.equals("true") || v.equals("false")) ? "" : "str";
    };
  }

  private static boolean allMatch(List<String> values, Pattern pattern) {
    return values.stream().allMatch(v -> pattern.matcher(v).matches());
  }

  private static String enumLiteral(String base, String value) {
    return switch (base) {
      case "str" -> PyClientView.quote(value);
      case "int", "float" -> value;
      default -> value.equals("true") ? "True" : "False";
    };
  }

  /** The definitions in dependency order: each one after the definitions it refers to. */
  private List<Definition> ordered() {
    Map<String, Definition> byName = new HashMap<>();
    for (Definition d : defs) {
      byName.put(d.name, d);
    }
    List<Definition> out = new ArrayList<>();
    Set<Definition> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Definition d : defs) {
      visit(d, byName, visited, out);
    }
    return out;
  }

  private static void visit(
      Definition d, Map<String, Definition> byName, Set<Definition> visited, List<Definition> out) {
    if (!visited.add(d)) {
      return;
    }
    for (String dep : d.deps) {
      visit(byName.get(dep), byName, visited, out);
    }
    out.add(d);
  }

  private static String importLines(Set<String> used) {
    List<String> stdlib = new ArrayList<>();
    if (used.contains("Enum")) {
      stdlib.add("from enum import Enum");
    }
    List<String> typing = used.stream().filter(TYPING::contains).toList();
    if (!typing.isEmpty()) {
      stdlib.add("from typing import " + String.join(", ", typing));
    }
    List<String> pydantic = used.stream().filter(PYDANTIC::contains).toList();
    List<String> groups = new ArrayList<>();
    if (!stdlib.isEmpty()) {
      groups.add(String.join("\n", stdlib));
    }
    if (!pydantic.isEmpty()) {
      groups.add("from pydantic import " + String.join(", ", pydantic));
    }
    return String.join("\n\n", groups);
  }

  /** {@code base}, or {@code base} with the lowest number from 2 that is free, recorded in used. */
  static String unique(String base, Set<String> used) {
    String name = base;
    for (int i = 2; !used.add(name); i++) {
      name = base + i;
    }
    return name;
  }
}
