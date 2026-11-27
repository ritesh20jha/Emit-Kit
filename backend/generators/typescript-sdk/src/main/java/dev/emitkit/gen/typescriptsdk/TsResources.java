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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource classes. Operations are grouped by the file name of their tag, so colliding tags such as
 * {@code user} and {@code User} share one class. Each operation becomes one method: {@code params}
 * holds the path, query, header and cookie parameters by key, and {@code body} is optional.
 */
final class TsResources {

  private static final Pattern PATH_TOKEN = Pattern.compile("\\{([^}]*)}");
  private static final Pattern SUCCESS = Pattern.compile("2(\\d\\d|XX)", Pattern.CASE_INSENSITIVE);

  /** Header names, lower-cased, that a parameter must not set: OpenAPI says clients ignore them. */
  private static final Set<String> RESERVED_HEADERS =
      Set.of("authorization", "accept", "content-type");

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
    Map<String, List<String>> byKey = new LinkedHashMap<>();
    for (String tag : tags) {
      byKey.computeIfAbsent(sameResource(tag), k -> new ArrayList<>()).add(tag);
    }
    List<Group> groups = new ArrayList<>();
    for (var entry : byKey.entrySet()) {
      String first = entry.getValue().get(0);
      List<Operation> operations =
          model.operations().stream()
              .filter(op -> entry.getKey().equals(sameResource(op.tag())))
              .toList();
      groups.add(
          new Group(
              TsNames.resourceFile(first),
              TsNames.resourceClass(first),
              clientMember(TsNames.resourceMember(first)),
              String.join(", ", entry.getValue()),
              operations));
    }
    return groups;
  }

  /**
   * A parameter as it appears on {@code params}: its key, which differs from the spec name when
   * renamed.
   */
  private record Input(Parameter param, String key) {}

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

  /**
   * Tags with equal keys share one resource. The class name, lowercased, is equal whenever the file
   * or class names are equal, so two tags that would clash in client.ts or index.ts merge instead.
   */
  private static String sameResource(String tag) {
    return TsNames.resourceClass(tag).toLowerCase(Locale.ROOT);
  }

  /** The client property name for a resource, suffixed when it would shadow a client member. */
  static String clientMember(String member) {
    return CLIENT_MEMBERS.contains(member) ? member + "_" : member;
  }

  private static String method(Operation op, String name, TsTypeRenderer types) {
    List<Input> params = inputs(op);
    RequestBody body = op.requestBody();
    boolean bodyRequired = body != null && body.required();
    List<String> signature = new ArrayList<>();
    if (!params.isEmpty()) {
      boolean anyRequired = params.stream().anyMatch(i -> required(i.param()));
      String fields =
          params.stream()
              .map(i ->
                  TsTypeRenderer.propertyKey(i.key())
                      + (required(i.param()) ? "" : "?")
                      + ": "
                      + types.type(i.param().schema()))
              .collect(joining("; ", "{ ", " }"));
      // A required body follows the params, so the params have no default to hide behind.
      boolean defaults = !anyRequired && !bodyRequired;
      signature.add("params: " + fields + (defaults ? " = {}" : ""));
    }
    if (body != null) {
      signature.add((bodyRequired ? "body: " : "body?: ") + types.type(body.schema()));
    }
    Response success = success(op);
    boolean returnsBody =
        success != null && success.schema() != null && !"204".equals(success.status());
    String returns =
        returnsBody ? "Promise<" + types.type(success.schema()) + ">" : "Promise<void>";

    List<String> options = new ArrayList<>();
    List<String> query = entries(params, ParamLocation.QUERY);
    if (!query.isEmpty()) {
      options.add("      query: { " + String.join(", ", query) + " },");
    }
    List<String> headers = entries(params, ParamLocation.HEADER);
    if (!headers.isEmpty()) {
      options.add("      headers: { " + String.join(", ", headers) + " },");
    }
    List<String> cookies = entries(params, ParamLocation.COOKIE);
    if (!cookies.isEmpty()) {
      options.add("      cookies: { " + String.join(", ", cookies) + " },");
    }
    if (!op.security().isEmpty()) {
      List<String> schemes = op.security().stream().map(TsClientView::quote).toList();
      options.add("      security: [" + String.join(", ", schemes) + "],");
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

  /**
   * The path, query, header and cookie parameters of {@code op}, each with its params key. One
   * parameter per location and name: the first declaration wins. Reserved headers are skipped. A
   * key already taken by a parameter in another location is renamed with that location as a prefix,
   * such as {@code headerLimit}; the wire name is unchanged.
   */
  private static List<Input> inputs(Operation op) {
    Set<String> seen = new HashSet<>();
    Set<String> keys = new HashSet<>();
    List<Input> inputs = new ArrayList<>();
    for (Parameter p : op.parameters()) {
      if (p.in() == ParamLocation.HEADER
          && RESERVED_HEADERS.contains(p.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      if (!seen.add(p.in() + " " + p.name())) {
        continue;
      }
      String base = baseKey(p);
      String key =
          keys.add(base)
              ? base
              : unique(p.in().name().toLowerCase(Locale.ROOT) + capitalise(base), keys);
      inputs.add(new Input(p, key));
    }
    return List.copyOf(inputs);
  }

  /**
   * The natural params key of a parameter. Path and query parameters keep their spec name, and
   * header and cookie parameters use the camel-case member name, such as {@code idempotencyKey}.
   */
  private static String baseKey(Parameter p) {
    return p.in() == ParamLocation.HEADER || p.in() == ParamLocation.COOKIE
        ? TsNames.headerOrCookieKey(p.name())
        : p.name();
  }

  private static String capitalise(String word) {
    return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }

  /** Object entries {@code "name": params.key} for the parameters in {@code location}. */
  private static List<String> entries(List<Input> params, ParamLocation location) {
    return params.stream()
        .filter(i -> i.param().in() == location)
        .map(i -> TsTypeRenderer.propertyKey(i.param().name()) + ": " + access(i.key()))
        .toList();
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
  private static String path(String template, List<Input> params) {
    List<String> parts = new ArrayList<>();
    Matcher m = PATH_TOKEN.matcher(template);
    int last = 0;
    while (m.find()) {
      String name = m.group(1);
      Input param =
          params.stream()
              .filter(i -> i.param().in() == ParamLocation.PATH && i.param().name().equals(name))
              .findFirst()
              .orElse(null);
      if (param == null) {
        continue;
      }
      if (m.start() > last) {
        parts.add(TsClientView.quote(template.substring(last, m.start())));
      }
      parts.add("encodeURIComponent(String(" + access(param.key()) + "))");
      last = m.end();
    }
    if (last < template.length() || parts.isEmpty()) {
      parts.add(TsClientView.quote(template.substring(last)));
    }
    return String.join(" + ", parts);
  }

  /**
   * A JSDoc block for the operation, or empty when it has no text and is not deprecated. The
   * summary comes first, then a blank line and the description, unless the description is missing
   * or the same as the summary.
   */
  private static String doc(Operation op) {
    String summary = op.summary() == null ? "" : TsClientView.docText(op.summary());
    String description = op.description() == null ? "" : TsClientView.docText(op.description());
    List<String> lines = new ArrayList<>();
    if (!summary.isEmpty()) {
      lines.add(summary);
    }
    if (!description.isEmpty() && !description.equals(summary)) {
      if (!summary.isEmpty()) {
        lines.add("");
      }
      lines.add(description);
    }
    if (!op.deprecated() && lines.size() <= 1) {
      return lines.isEmpty() ? "" : "  /** " + lines.get(0) + " */\n";
    }
    StringBuilder out = new StringBuilder("  /**\n");
    for (String line : lines) {
      out.append(line.isEmpty() ? "   *" : "   * " + line).append('\n');
    }
    if (op.deprecated()) {
      out.append("   * @deprecated\n");
    }
    return out.append("   */\n").toString();
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
