package dev.emitkit.gen.javasdk;

import dev.emitkit.gen.javasdk.JavaTypeRenderer.Scope;
import dev.emitkit.gen.javasdk.JavaTypeRenderer.TypeFile;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One {@code {Tag}Api} class per resolved tag. Methods take the required parameters, then the body,
 * then an {@code {Op}Options} record for optional parameters. Query, header and cookie parameters
 * are sent with the request.
 */
final class JavaResourceRenderer {

  private static final String INDENT = "  ";
  private static final int MAX_LINE = 100;

  private static final String SEGMENT_HELPER =
      """
        /** Percent-encodes one path segment. URLEncoder writes spaces as '+', so use %20. */
        private static String segment(String value) {
          return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
      """;

  private static final String READ_HELPER =
      """
        private <T> T read(HttpResponse<String> response, TypeReference<T> type) {
          try {
            return client.mapper().readValue(response.body(), type);
          } catch (JsonProcessingException e) {
            throw new IllegalStateException("The response body is not the expected JSON", e);
          }
        }
      """;

  /** One resource: its class name, the client accessor name, the tags it groups, and operations. */
  record Group(String className, String member, List<String> tags, List<Operation> operations) {}

  private JavaResourceRenderer() {}

  /** The resource groups of the API, in order of first appearance. */
  static List<Group> groups(ApiModel model) {
    NameStrategy java = NameStrategy.forLanguage(Language.JAVA);
    Map<String, List<Operation>> byClass =
        model.operations().stream()
            .collect(
                Collectors.groupingBy(
                    op -> java.type(op.tag()) + "Api", LinkedHashMap::new, Collectors.toList()));
    return byClass.entrySet().stream()
        .map(e ->
            new Group(
                e.getKey(),
                java.member(e.getValue().get(0).tag()),
                e.getValue().stream().map(Operation::tag).distinct().toList(),
                e.getValue()))
        .toList();
  }

  /** The resource class files, one per group. */
  static List<TypeFile> files(ApiModel model, JavaTypeRenderer types, JavaClientView view) {
    return groups(model).stream()
        .map(group -> file(group, types, view, model.securitySchemes()))
        .toList();
  }

  private static TypeFile file(
      Group group,
      JavaTypeRenderer types,
      JavaClientView view,
      Map<String, SecurityScheme> schemes) {
    List<Operation> ops = group.operations();
    boolean usesPath = ops.stream().anyMatch(op -> hasParam(op, ParamLocation.PATH));
    boolean usesQuery = ops.stream().anyMatch(op -> hasParam(op, ParamLocation.QUERY));
    boolean usesHeaders =
        ops.stream()
            .anyMatch(
                op -> hasParam(op, ParamLocation.HEADER) || hasParam(op, ParamLocation.COOKIE));
    boolean usesCookies = ops.stream().anyMatch(op -> hasParam(op, ParamLocation.COOKIE));
    boolean usesResponse = ops.stream().anyMatch(op -> successResponse(op) != null);
    String client = view.className();

    Set<String> imports = new TreeSet<>();
    imports.add(view.packageName() + "." + client);
    imports.add("java.util.List");
    if (usesResponse) {
      imports.add("com.fasterxml.jackson.core.JsonProcessingException");
      imports.add("com.fasterxml.jackson.core.type.TypeReference");
      imports.add("java.net.http.HttpResponse");
    }
    if (ops.stream()
        .anyMatch(op -> params(op).stream().anyMatch(p -> isArray(types, p.schema())))) {
      imports.add("java.util.stream.Collectors");
    }
    if (usesPath) {
      imports.add("java.net.URLEncoder");
      imports.add("java.nio.charset.StandardCharsets");
    }
    if (usesQuery || usesHeaders || usesCookies) {
      imports.add("java.util.LinkedHashMap");
      imports.add("java.util.Map");
    }

    Scope api = new Scope("", true, imports);
    // Nested type names must not shadow the model types, the client, the resource class, or the
    // {Op}Options and {Op}Response types that the methods declare.
    Set<String> models = types.namedTypes();
    Set<String> taken = new HashSet<>(models);
    taken.add(client);
    taken.add(group.className());
    for (Operation op : ops) {
      taken.add(base(op) + "Options");
      taken.add(base(op) + "Response");
    }
    Ctx ctx = new Ctx(types, schemes, models, taken);
    StringBuilder methods = new StringBuilder();
    Map<String, String> methodNames = new HashMap<>();
    for (Operation op : ops) {
      String detail = "operations of " + group.className();
      JavaTypeRenderer.claim(methodNames, javaMethodName(op), op.operationId(), detail);
      methods.append(method(op, ctx, api));
    }
    StringBuilder out = new StringBuilder();
    out.append("/** Operations tagged ")
        .append(JavaClientView.docText(String.join(", ", group.tags())))
        .append(". */\n");
    out.append("public final class ").append(group.className()).append(" {\n\n");
    out.append(INDENT).append("private final ").append(client).append(" client;\n\n");
    out.append(INDENT).append("public ").append(group.className());
    out.append('(').append(client).append(" client) {\n");
    out.append(INDENT).append(INDENT).append("this.client = client;\n");
    out.append(INDENT).append("}\n");
    out.append(methods);
    if (usesPath) {
      out.append('\n').append(SEGMENT_HELPER);
    }
    if (usesResponse) {
      out.append('\n').append(READ_HELPER);
    }
    for (String nested : api.nested) {
      out.append('\n').append(nested);
    }
    return new TypeFile(group.className(), imports, out.append("}\n").toString());
  }

  /** Header names that OpenAPI says clients ignore. A header parameter with one is not sent. */
  private static final Set<String> IGNORED_HEADERS =
      Set.of("authorization", "accept", "content-type");

  /** Locals the generated method bodies use. A parameter with one of these names gets a _. */
  private static final Set<String> RESERVED_LOCALS =
      Set.of("query", "headers", "cookies", "options", "body", "response", "client", "item");

  /** Helper methods the resource classes declare. An operation with one of these gets a _. */
  private static final Set<String> RESERVED_METHODS =
      Stream.concat(Stream.of("segment", "read"), JavaTypeRenderer.OBJECT_METHODS.stream())
          .collect(Collectors.toUnmodifiableSet());

  /** One operation: its method, and the options record it takes, if any, added to {@code api}. */
  /** What a method needs from its resource class: the type renderer, the schemes and the names. */
  private record Ctx(
      JavaTypeRenderer types,
      Map<String, SecurityScheme> schemes,
      /** Java names of the model types, which a nested type must not shadow. */
      Set<String> models,
      /** Names the resource class's nested types and members already use. */
      Set<String> taken) {}

  private static String method(Operation op, Ctx ctx, Scope api) {
    JavaTypeRenderer types = ctx.types();
    String base = base(op);
    String methodName = javaMethodName(op);
    List<String> args = new ArrayList<>();
    Map<String, String> pathValues = new LinkedHashMap<>();
    Set<String> names = new HashSet<>();
    StringBuilder sendLines = new StringBuilder();
    List<String> optionComps = new ArrayList<>();
    Scope options = api.nestedScope();
    boolean arrayQuery = false;
    for (Parameter p : params(op)) {
      String name = paramName(p, names);
      boolean required = p.required() || p.in() == ParamLocation.PATH;
      if (required) {
        args.add(paramType(ctx, p.schema(), true, base, api, p.name()) + " " + name);
      }
      if (p.in() == ParamLocation.PATH) {
        pathValues.put(p.name(), wire(types, p.schema(), name));
        continue;
      }
      arrayQuery |= p.in() == ParamLocation.QUERY && isArray(types, p.schema());
      if (required) {
        String send = send(p.in(), p.name(), wire(types, p.schema(), name));
        sendLines.append("    ").append(send).append('\n');
      } else {
        optionComps.add(paramType(ctx, p.schema(), false, base, api, p.name()) + " " + name);
        String send = send(p.in(), p.name(), wire(types, p.schema(), "options." + name + "()"));
        sendLines.append("    if (options != null && options.").append(name);
        sendLines.append("() != null) {\n");
        sendLines.append("      ").append(send).append('\n');
        sendLines.append("    }\n");
      }
    }
    RequestBody body = op.requestBody();
    if (body != null) {
      args.add(types.type(body.schema(), body.required(), base + "Request", api) + " body");
    }
    if (!optionComps.isEmpty()) {
      if (ctx.models().contains(base + "Options")) {
        throw new IllegalArgumentException(
            "operation %s: its options type %sOptions would shadow the model of that name"
                .formatted(op.operationId(), base));
      }
      api.nested.add(types.record(options, base + "Options", null, optionComps, ""));
      args.add(base + "Options options");
    }
    Response response = successResponse(op);
    String ret =
        response == null ? "void" : types.type(response.schema(), false, base + "Response", api);
    // A response that is not a reference may declare the nested type {Op}Response, so it must not
    // be a model name that the resource file imports.
    if (response != null
        && !(response.schema() instanceof RefSchema)
        && ctx.models().contains(base + "Response")) {
      throw new IllegalArgumentException(
          "operation %s: its response type %sResponse would shadow the model of that name"
              .formatted(op.operationId(), base));
    }

    boolean anyQuery = hasParam(op, ParamLocation.QUERY);
    boolean anyHeaders = hasParam(op, ParamLocation.HEADER);
    boolean anyCookies = hasParam(op, ParamLocation.COOKIE);
    String method = op.method().toUpperCase(Locale.ROOT);
    List<String> callArgs = new ArrayList<>();
    callArgs.add(JavaClientView.quote(method));
    callArgs.add(pathExpression(op.path(), pathValues));
    callArgs.add(anyQuery ? "query" : "null");
    callArgs.add(anyHeaders ? "headers" : "null");
    callArgs.add(anyCookies ? "cookies" : "null");
    callArgs.add(body == null ? "null" : "body");
    callArgs.add(securityList(op, ctx.schemes()));
    List<String> paragraphs = docParagraphs(op, method);
    if (arrayQuery) {
      paragraphs.add("<p>Array query parameters are sent comma-separated.");
    }

    StringBuilder out = new StringBuilder();
    out.append('\n');
    out.append(javadoc(paragraphs));
    out.append(INDENT).append("public ").append(ret).append(' ').append(methodName);
    out.append('(').append(String.join(", ", args)).append(") {\n");
    if (anyQuery) {
      out.append("    Map<String, String> query = new LinkedHashMap<>();\n");
    }
    if (anyHeaders) {
      out.append("    Map<String, String> headers = new LinkedHashMap<>();\n");
    }
    if (anyCookies) {
      out.append("    Map<String, String> cookies = new LinkedHashMap<>();\n");
    }
    out.append(sendLines);
    if (response == null) {
      out.append(statement("", callArgs));
    } else {
      out.append(statement("HttpResponse<String> response = ", callArgs));
      out.append("    return read(response, new TypeReference<").append(ret).append(">() {});\n");
    }
    return out.append(INDENT).append("}\n").toString();
  }

  /** The statement that sends one parameter, given its value as a string. */
  private static String send(ParamLocation in, String name, String value) {
    String key = JavaClientView.quote(name);
    return switch (in) {
      case QUERY -> "query.put(" + key + ", " + value + ");";
      case HEADER -> "headers.put(" + key + ", " + value + ");";
      case COOKIE -> "cookies.put(" + key + ", " + value + ");";
      case PATH -> throw new IllegalArgumentException("path parameters are not sent separately");
    };
  }

  /**
   * The Java type of a parameter. An inline enum, or an array of one, is declared in the resource
   * class as {@code {Op}{Param}}, so the options record and the methods can both name it.
   */
  private static String paramType(
      Ctx ctx, Schema schema, boolean required, String base, Scope api, String paramName) {
    String hint = null;
    if (isInlineEnum(schema)) {
      String candidate = base + NameStrategy.forLanguage(Language.JAVA).type(paramName);
      hint = claimName(candidate, ctx.taken());
    }
    return ctx.types().type(schema, required, hint, api);
  }

  /**
   * Claims a nested type name: the candidate, else the candidate with Param, else with a number.
   */
  private static String claimName(String candidate, Set<String> taken) {
    String name = candidate;
    for (int n = 1; taken.contains(name); n++) {
      name = n == 1 ? candidate + "Param" : candidate + "Param" + n;
    }
    taken.add(name);
    return name;
  }

  /** The operation's base name, from its operation id, which prefixes its nested types. */
  private static String base(Operation op) {
    return NameStrategy.forLanguage(Language.JAVA).type(op.operationId());
  }

  /**
   * The parameters that are sent. Header parameters that OpenAPI says clients ignore are dropped,
   * and a repeated name in one location keeps its first declaration.
   */
  private static List<Parameter> params(Operation op) {
    Set<String> seen = new HashSet<>();
    List<Parameter> out = new ArrayList<>();
    for (Parameter p : op.parameters()) {
      boolean ignored =
          p.in() == ParamLocation.HEADER
              && IGNORED_HEADERS.contains(p.name().toLowerCase(Locale.ROOT));
      if (!ignored && seen.add(p.in() + " " + p.name())) {
        out.add(p);
      }
    }
    return out;
  }

  /**
   * A Java name for a parameter that no other parameter of the operation has: its member name, else
   * its location and member name ({@code headerLimit}), else that with a number. The wire name is
   * unchanged. A name never shadows a local the method uses or an Object method.
   */
  private static String paramName(Parameter p, Set<String> claimed) {
    String member = NameStrategy.forLanguage(Language.JAVA).member(p.name());
    String prefixed = p.in().name().toLowerCase(Locale.ROOT) + capitalise(member);
    String plain = avoidReserved(member);
    if (claimed.add(plain)) {
      return plain;
    }
    String withLocation = avoidReserved(prefixed);
    if (claimed.add(withLocation)) {
      return withLocation;
    }
    for (int n = 2; ; n++) {
      String numbered = avoidReserved(prefixed + n);
      if (claimed.add(numbered)) {
        return numbered;
      }
    }
  }

  private static String avoidReserved(String name) {
    boolean reserved =
        RESERVED_LOCALS.contains(name) || JavaTypeRenderer.OBJECT_METHODS.contains(name);
    return reserved ? name + "_" : name;
  }

  private static String capitalise(String word) {
    return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }

  private static boolean isInlineEnum(Schema schema) {
    return schema instanceof EnumSchema
        || (schema instanceof ArraySchema a && isInlineEnum(a.items()));
  }

  private static boolean isArray(JavaTypeRenderer types, Schema schema) {
    if (schema instanceof RefSchema r) {
      return isArray(types, types.target(r.name()));
    }
    return schema instanceof ArraySchema;
  }

  /**
   * A call to the client at four spaces. It wraps after {@code lhs}, or breaks its arguments, when
   * the line would run past the limit.
   */
  private static String statement(String lhs, List<String> args) {
    String call = "client.request(" + String.join(", ", args) + ");";
    String single = "    " + lhs + call;
    if (single.length() <= MAX_LINE) {
      return single + "\n";
    }
    String start = lhs.isEmpty() ? "" : "    " + lhs.strip() + "\n";
    String indent = lhs.isEmpty() ? "    " : "        ";
    if (indent.length() + call.length() <= MAX_LINE) {
      return start + indent + call + "\n";
    }
    String argIndent = indent + "    ";
    String argLines = args.stream().map(a -> argIndent + a).collect(Collectors.joining(",\n"));
    return start + indent + "client.request(\n" + argLines + ");\n";
  }

  /**
   * The paragraphs of an operation's javadoc: its summary, then its description when there is one
   * and it differs from the summary. The summary falls back to the method and path.
   */
  private static List<String> docParagraphs(Operation op, String method) {
    String summary =
        JavaClientView.docText(op.summary() != null ? op.summary() : method + " " + op.path());
    List<String> paragraphs = new ArrayList<>();
    paragraphs.add(summary);
    String description = op.description() == null ? "" : JavaClientView.docText(op.description());
    if (!description.isEmpty() && !description.equals(summary)) {
      paragraphs.add("<p>" + description);
    }
    return paragraphs;
  }

  /** A javadoc comment at the class's member indentation, one line when there is one paragraph. */
  private static String javadoc(List<String> paragraphs) {
    if (paragraphs.size() == 1) {
      return INDENT + "/** " + paragraphs.get(0) + " */\n";
    }
    StringBuilder out = new StringBuilder(INDENT).append("/**\n");
    for (int i = 0; i < paragraphs.size(); i++) {
      if (i > 0) {
        out.append(INDENT).append(" *\n");
      }
      out.append(INDENT).append(" * ").append(paragraphs.get(i)).append('\n');
    }
    return out.append(INDENT).append(" */\n").toString();
  }

  /**
   * The operation's security requirement as a List of alternatives, each a List of scheme names.
   * Within an alternative, its apiKey schemes are reduced to one, the key it sends: the first that
   * names a header or cookie, else the first that names a query parameter. The other schemes are
   * kept in the order the alternative lists them. The client chooses the alternative at run time.
   */
  private static String securityList(Operation op, Map<String, SecurityScheme> schemes) {
    return op.securityRequirements().stream()
        .map(alternative ->
            "List.of("
                + oneApiKey(alternative, schemes).stream()
                    .map(JavaClientView::quote)
                    .collect(Collectors.joining(", "))
                + ")")
        .collect(Collectors.joining(", ", "List.of(", ")"));
  }

  private static List<String> oneApiKey(List<String> names, Map<String, SecurityScheme> schemes) {
    List<String> keys =
        names.stream().filter(n -> JavaClientView.isSendableApiKey(schemes.get(n))).toList();
    String chosen =
        keys.stream()
            .filter(n -> !"query".equals(schemes.get(n).in()))
            .findFirst()
            .orElse(keys.isEmpty() ? null : keys.get(0));
    List<String> sent = new ArrayList<>();
    for (String name : names) {
      SecurityScheme scheme = schemes.get(name);
      boolean apiKey = scheme != null && scheme.kind() == SecurityScheme.Kind.API_KEY;
      if (!apiKey || name.equals(chosen)) {
        sent.add(name);
      }
    }
    return sent;
  }

  /** The 2xx response that carries a body, the first in document order, or null. */
  private static Response successResponse(Operation op) {
    return op.responses().values().stream()
        .filter(r -> r.status().startsWith("2") && r.schema() != null)
        .findFirst()
        .orElse(null);
  }

  /** The Java method name for an operation: its member name, with a _ when reserved. */
  private static String javaMethodName(Operation op) {
    String member = NameStrategy.forLanguage(Language.JAVA).member(op.operationId());
    return RESERVED_METHODS.contains(member) ? member + "_" : member;
  }

  private static boolean hasParam(Operation op, ParamLocation in) {
    return params(op).stream().anyMatch(p -> p.in() == in);
  }

  /** A Java expression for the path: literal segments joined to encoded parameter values. */
  private static String pathExpression(String path, Map<String, String> values) {
    Matcher matcher = Pattern.compile("\\{([^}]+)}").matcher(path);
    List<String> parts = new ArrayList<>();
    int from = 0;
    while (matcher.find()) {
      addLiteral(parts, path.substring(from, matcher.start()));
      String value = values.get(matcher.group(1));
      if (value == null) {
        throw new IllegalArgumentException(
            "path parameter " + matcher.group(1) + " of " + path + " is not declared");
      }
      parts.add("segment(" + value + ")");
      from = matcher.end();
    }
    addLiteral(parts, path.substring(from));
    return String.join(" + ", parts);
  }

  private static void addLiteral(List<String> parts, String text) {
    if (!text.isEmpty()) {
      parts.add(JavaClientView.quote(text));
    }
  }

  /** The string a value is sent as. Enums send their wire value and arrays join theirs by comma. */
  private static String wire(JavaTypeRenderer types, Schema schema, String expr) {
    if (schema instanceof RefSchema r) {
      Schema target = types.target(r.name());
      if (target instanceof EnumSchema) {
        return "String.valueOf(" + expr + ".value())";
      }
      return wire(types, target, expr);
    }
    if (schema instanceof EnumSchema) {
      return "String.valueOf(" + expr + ".value())";
    }
    if (schema instanceof ArraySchema a) {
      if (a.items() instanceof ArraySchema) {
        throw new IllegalArgumentException("arrays of arrays cannot be sent as a parameter");
      }
      String item = wire(types, a.items(), "item");
      return expr + ".stream().map(item -> " + item + ").collect(Collectors.joining(\",\"))";
    }
    if (schema instanceof PrimitiveSchema s && s.type() == PrimitiveType.STRING) {
      return expr;
    }
    return "String.valueOf(" + expr + ")";
  }
}
