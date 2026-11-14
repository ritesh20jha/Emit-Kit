package dev.emitkit.gen.typescriptsdk;

import static java.util.stream.Collectors.joining;

import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource classes. Operations are grouped by the file name of their tag, so colliding tags such as
 * {@code user} and {@code User} share one class. Each operation becomes one method: {@code params}
 * holds the path and query parameters keyed by their spec names, and {@code body} is optional.
 */
final class TsResources {

  private static final Pattern PATH_TOKEN = Pattern.compile("\\{([^}]*)}");
  private static final Pattern SUCCESS = Pattern.compile("2(\\d\\d|XX)", Pattern.CASE_INSENSITIVE);

  /** Client members a resource property must not shadow. */
  private static final Set<String> CLIENT_MEMBERS =
      Set.of(
          "request",
          "constructor",
          "baseUrl",
          "fetchImpl",
          "maxRetries",
          "authHeaders",
          "authQuery");

  private TsResources() {}

  /**
   * One resource: the operations of every tag that maps to {@code file}. {@code tags} is the
   * comma-separated list of those tags, the first of which names the class.
   */
  record Group(
      String file, String className, String member, String tags, List<Operation> operations) {}

  /** Groups operations by resource file, in tag model order. */
  static List<Group> groups(ApiModel model) {
    List<String> order = model.tags().stream().map(Tag::name).toList();
    List<String> tags =
        model.operations().stream()
            .map(Operation::tag)
            .distinct()
            .sorted(
                Comparator.comparingInt(
                    (String t) -> order.indexOf(t) < 0 ? Integer.MAX_VALUE : order.indexOf(t)))
            .toList();
    Map<String, List<String>> byFile = new LinkedHashMap<>();
    for (String tag : tags) {
      byFile.computeIfAbsent(TsNames.resourceFile(tag), f -> new ArrayList<>()).add(tag);
    }
    List<Group> groups = new ArrayList<>();
    for (var entry : byFile.entrySet()) {
      String first = entry.getValue().get(0);
      List<Operation> operations =
          model.operations().stream()
              .filter(op -> entry.getKey().equals(TsNames.resourceFile(op.tag())))
              .toList();
      groups.add(
          new Group(
              entry.getKey(),
              TsNames.resourceClass(first),
              clientMember(TsNames.resourceMember(first)),
              String.join(", ", entry.getValue()),
              operations));
    }
    return groups;
  }

  /** The full source of one resource file. */
  static String render(Templates templates, Group group, TsNames names) {
    TsTypeRenderer types = new TsTypeRenderer(names);
    Set<String> used = new HashSet<>();
    List<String> methods = new ArrayList<>();
    for (Operation op : group.operations()) {
      String name = unique(TsNames.methodName(op.operationId()), used);
      methods.add("\n" + method(op, name, types));
    }
    Set<String> imports = types.takeReferenced();
    return templates.render(
        "resource.ts",
        Map.of(
            "className", group.className(),
            "tags", TsClientView.docText(group.tags()),
            "hasTypes", !imports.isEmpty(),
            "typeList", String.join(", ", imports),
            "methods", String.join("\n", methods)));
  }

  /** The client property name for a resource, suffixed when it would shadow a client member. */
  static String clientMember(String member) {
    return CLIENT_MEMBERS.contains(member) ? member + "_" : member;
  }

  private static String method(Operation op, String name, TsTypeRenderer types) {
    List<Parameter> params = inputs(op);
    RequestBody body = op.requestBody();
    List<String> signature = new ArrayList<>();
    if (!params.isEmpty()) {
      boolean anyRequired = params.stream().anyMatch(TsResources::required);
      String fields =
          params.stream()
              .map(p ->
                  TsTypeRenderer.propertyKey(p.name())
                      + (required(p) ? "" : "?")
                      + ": "
                      + types.type(p.schema()))
              .collect(joining("; ", "{ ", " }"));
      signature.add("params: " + fields + (anyRequired ? "" : " = {}"));
    }
    if (body != null) {
      signature.add("body?: " + types.type(body.schema()));
    }
    Response success = success(op);
    boolean returnsBody =
        success != null && success.schema() != null && !"204".equals(success.status());
    String returns =
        returnsBody ? "Promise<" + types.type(success.schema()) + ">" : "Promise<void>";

    List<String> options = new ArrayList<>();
    List<String> query =
        params.stream()
            .filter(p -> p.in() == ParamLocation.QUERY)
            .map(p -> TsTypeRenderer.propertyKey(p.name()) + ": " + access(p.name()))
            .toList();
    if (!query.isEmpty()) {
      options.add("      query: { " + String.join(", ", query) + " },");
    }
    if (body != null) {
      options.add("      body,");
    }
    String request =
        "this.client.request("
            + TsClientView.quote(op.method())
            + ", "
            + path(op.path(), params)
            + (options.isEmpty() ? ")" : ", {\n" + String.join("\n", options) + "\n    })");

    StringBuilder out = new StringBuilder(doc(op));
    out.append("  async ")
        .append(name)
        .append('(')
        .append(String.join(", ", signature))
        .append("): ")
        .append(returns)
        .append(" {\n");
    if (returnsBody) {
      out.append("    const response = await ").append(request).append(";\n");
      out.append("    return response.json();\n");
    } else {
      out.append("    await ").append(request).append(";\n");
    }
    return out.append("  }").toString();
  }

  /** Path and query parameters, one per name: the first declaration of a name wins. */
  private static List<Parameter> inputs(Operation op) {
    Map<String, Parameter> byName = new LinkedHashMap<>();
    for (Parameter p : op.parameters()) {
      if (p.in() == ParamLocation.PATH || p.in() == ParamLocation.QUERY) {
        byName.putIfAbsent(p.name(), p);
      }
    }
    return List.copyOf(byName.values());
  }

  private static boolean required(Parameter p) {
    return p.in() == ParamLocation.PATH || p.required();
  }

  /** The first 2xx response, which decides the return type. */
  private static Response success(Operation op) {
    for (Response response : op.responses().values()) {
      if (SUCCESS.matcher(response.status()).matches()) {
        return response;
      }
    }
    return null;
  }

  /** A property access on {@code params}: dotted when the name is an identifier, else bracketed. */
  private static String access(String name) {
    return TsTypeRenderer.isIdentifier(name)
        ? "params." + name
        : "params[" + TsClientView.quote(name) + "]";
  }

  /**
   * The request path as a concatenation: literal text is quoted and each path parameter is {@code
   * encodeURIComponent}-ed. A {@code {token}} with no matching path parameter stays literal.
   */
  private static String path(String template, List<Parameter> params) {
    List<String> parts = new ArrayList<>();
    Matcher m = PATH_TOKEN.matcher(template);
    int last = 0;
    while (m.find()) {
      String name = m.group(1);
      boolean isParam =
          params.stream().anyMatch(p -> p.in() == ParamLocation.PATH && p.name().equals(name));
      if (!isParam) {
        continue;
      }
      if (m.start() > last) {
        parts.add(TsClientView.quote(template.substring(last, m.start())));
      }
      parts.add("encodeURIComponent(String(" + access(name) + "))");
      last = m.end();
    }
    if (last < template.length() || parts.isEmpty()) {
      parts.add(TsClientView.quote(template.substring(last)));
    }
    return String.join(" + ", parts);
  }

  /** A JSDoc block for the operation, or empty when it has no text and is not deprecated. */
  private static String doc(Operation op) {
    List<String> parts = new ArrayList<>();
    for (String text : new String[] {op.summary(), op.description()}) {
      if (text != null && !TsClientView.docText(text).isEmpty()) {
        parts.add(TsClientView.docText(text));
      }
    }
    String text = String.join(" ", parts);
    if (!op.deprecated()) {
      return text.isEmpty() ? "" : "  /** " + text + " */\n";
    }
    return "  /**\n"
        + (text.isEmpty() ? "" : "   * " + text + "\n")
        + "   * @deprecated\n"
        + "   */\n";
  }

  /** {@code name}, or the first of {@code name2}, {@code name3}, ... not yet in {@code used}. */
  private static String unique(String name, Set<String> used) {
    String candidate = name;
    for (int n = 2; !used.add(candidate); n++) {
      candidate = name + n;
    }
    return candidate;
  }
}
