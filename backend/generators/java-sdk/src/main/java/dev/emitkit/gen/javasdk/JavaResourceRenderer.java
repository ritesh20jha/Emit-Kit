package dev.emitkit.gen.javasdk;

import dev.emitkit.gen.javasdk.JavaTypeRenderer.Scope;
import dev.emitkit.gen.javasdk.JavaTypeRenderer.TypeFile;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One resource class per tag: {@code {Tag}Api} with a method for each operation. Tags that resolve
 * to the same class name share it, so {@code user} and {@code User} both give {@code UserApi}.
 * Untagged operations go to {@code DefaultApi}.
 *
 * <p>A method takes the required path and query parameters in document order, then the body, then
 * an {@code {Op}Options} record for the optional query parameters. Path values are percent-encoded
 * per segment. Header and cookie parameters are not sent.
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
        private static <T> T read(HttpResponse<String> response, TypeReference<T> type) {
          try {
            return MAPPER.readValue(response.body(), type);
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
    return groups(model).stream().map(group -> file(group, types, view)).toList();
  }

  private static TypeFile file(Group group, JavaTypeRenderer types, JavaClientView view) {
    List<Operation> ops = group.operations();
    boolean usesPath = ops.stream().anyMatch(op -> hasParam(op, ParamLocation.PATH));
    boolean usesQuery = ops.stream().anyMatch(op -> hasParam(op, ParamLocation.QUERY));
    boolean usesResponse = ops.stream().anyMatch(op -> successResponse(op) != null);
    String client = view.className();

    Set<String> imports = new TreeSet<>();
    imports.add(view.packageName() + "." + client);
    if (usesResponse) {
      imports.add("com.fasterxml.jackson.core.JsonProcessingException");
      imports.add("com.fasterxml.jackson.core.type.TypeReference");
      imports.add("com.fasterxml.jackson.databind.ObjectMapper");
      imports.add("java.net.http.HttpResponse");
    }
    if (usesPath) {
      imports.add("java.net.URLEncoder");
      imports.add("java.nio.charset.StandardCharsets");
    }
    if (usesQuery) {
      imports.add("java.util.LinkedHashMap");
      imports.add("java.util.Map");
    }

    Scope api = new Scope("", true, imports);
    StringBuilder methods = new StringBuilder();
    for (Operation op : ops) {
      methods.append(method(op, types, api));
    }
    StringBuilder out = new StringBuilder();
    out.append("/** Operations tagged ")
        .append(JavaClientView.docText(String.join(", ", group.tags())))
        .append(". */\n");
    out.append("public final class ").append(group.className()).append(" {\n\n");
    if (usesResponse) {
      out.append(INDENT)
          .append("private static final ObjectMapper MAPPER = new ObjectMapper();\n\n");
    }
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

  /** One operation: its method, and the options record it takes, if any, added to {@code api}. */
  private static String method(Operation op, JavaTypeRenderer types, Scope api) {
    NameStrategy java = NameStrategy.forLanguage(Language.JAVA);
    String base = java.type(op.operationId());
    List<String> args = new ArrayList<>();
    Map<String, String> pathValues = new LinkedHashMap<>();
    StringBuilder queryLines = new StringBuilder();
    List<String> optionComps = new ArrayList<>();
    Scope options = api.nestedScope();
    for (Parameter p : op.parameters()) {
      if (p.in() != ParamLocation.PATH && p.in() != ParamLocation.QUERY) {
        continue;
      }
      String name = java.member(p.name());
      boolean required = p.required() || p.in() == ParamLocation.PATH;
      if (required) {
        args.add(types.type(p.schema(), true, null, api) + " " + name);
      }
      if (p.in() == ParamLocation.PATH) {
        pathValues.put(p.name(), wire(types, p, name));
      } else if (required) {
        queryLines.append("    query.put(").append(JavaClientView.quote(p.name()));
        queryLines.append(", ").append(wire(types, p, name)).append(");\n");
      } else {
        optionComps.add(types.type(p.schema(), false, null, options) + " " + name);
        queryLines
            .append("    if (options != null && options.")
            .append(name)
            .append("() != null) {\n");
        queryLines.append("      query.put(").append(JavaClientView.quote(p.name()));
        queryLines.append(", ").append(wire(types, p, "options." + name + "()")).append(");\n");
        queryLines.append("    }\n");
      }
    }
    RequestBody body = op.requestBody();
    if (body != null) {
      args.add(types.type(body.schema(), body.required(), base + "Request", api) + " body");
    }
    if (!optionComps.isEmpty()) {
      api.nested.add(types.record(options, base + "Options", null, optionComps, ""));
      args.add(base + "Options options");
    }
    Response response = successResponse(op);
    String ret =
        response == null ? "void" : types.type(response.schema(), false, base + "Response", api);

    boolean anyQuery = hasParam(op, ParamLocation.QUERY);
    String method = op.method().toUpperCase(Locale.ROOT);
    List<String> callArgs = new ArrayList<>();
    callArgs.add(JavaClientView.quote(method));
    callArgs.add(pathExpression(op.path(), pathValues));
    callArgs.add(anyQuery ? "query" : "null");
    callArgs.add(body == null ? "null" : "body");
    String summary = op.summary() != null ? op.summary() : method + " " + op.path();

    StringBuilder out = new StringBuilder();
    out.append('\n').append(INDENT).append("/** ");
    out.append(JavaClientView.docText(summary)).append(" */\n");
    out.append(INDENT).append("public ").append(ret).append(' ');
    out.append(java.member(op.operationId())).append('(');
    out.append(String.join(", ", args)).append(") {\n");
    if (anyQuery) {
      out.append("    Map<String, String> query = new LinkedHashMap<>();\n");
      out.append(queryLines);
    }
    if (response == null) {
      out.append(statement("", callArgs));
    } else {
      out.append(statement("HttpResponse<String> response = ", callArgs));
      out.append("    return read(response, new TypeReference<").append(ret).append(">() {});\n");
    }
    return out.append(INDENT).append("}\n").toString();
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

  /** The 2xx response that carries a body, the first in document order, or null. */
  private static Response successResponse(Operation op) {
    return op.responses().values().stream()
        .filter(r -> r.status().startsWith("2") && r.schema() != null)
        .findFirst()
        .orElse(null);
  }

  private static boolean hasParam(Operation op, ParamLocation in) {
    return op.parameters().stream().anyMatch(p -> p.in() == in);
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

  /** The string a parameter value is sent as: enums send their wire value, others their text. */
  private static String wire(JavaTypeRenderer types, Parameter p, String expr) {
    if (p.schema() instanceof RefSchema r && types.target(r.name()) instanceof EnumSchema) {
      return "String.valueOf(" + expr + ".value())";
    }
    if (p.schema() instanceof PrimitiveSchema s && s.type() == PrimitiveType.STRING) {
      return expr;
    }
    return "String.valueOf(" + expr + ")";
  }
}
