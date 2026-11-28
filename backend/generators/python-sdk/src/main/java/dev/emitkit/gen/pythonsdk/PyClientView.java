package dev.emitkit.gen.pythonsdk;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Values rendered by the Python SDK templates. Fields named {@code *Literal} are already quoted as
 * string literals valid in Python and TOML; the others are plain text.
 *
 * @param className sync client class name, ending in {@code Client} exactly once
 * @param asyncClassName async client class name, {@code Async} followed by {@code className}
 * @param title single-line API title as plain text
 * @param docTitle {@code title} escaped for use inside a triple-quoted docstring
 * @param packageDir importable package directory, derived from the PyPI name
 * @param packageName PyPI distribution name
 * @param packageNameLiteral {@code packageName} as a string literal
 * @param versionLiteral package version as a string literal
 * @param descriptionLiteral one-line package description as a string literal
 * @param defaultBaseUrlLiteral default base URL as a string literal, or {@code ""} when none
 * @param defaultBaseUrl default base URL as plain text, or {@code ""} when none
 * @param hasDefaultBaseUrl whether a default base URL exists
 * @param baseUrlAdvice plain-text sentence telling the caller what base URL to pass
 * @param baseUrlErrorLiteral the constructor's base URL error message as a string literal
 * @param maxRetriesErrorLiteral the sync constructor's retry validation message as a string literal
 * @param asyncBaseUrlErrorLiteral the async constructor's base URL error as a string literal
 * @param asyncMaxRetriesErrorLiteral the async constructor's retry error as a string literal
 * @param hasApiKey whether the API declares a header or query apiKey scheme
 * @param hasBearer whether the API declares an HTTP bearer, OAuth2 or OpenID Connect scheme
 * @param hasBasic whether the API declares an HTTP basic scheme
 * @param schemeLocations one entry of the client's table per scheme whose credential the client
 *     sends: the scheme name, then where the credential goes
 * @param resources one resource per module, attached to each client under its attribute
 * @param modelNames the models the package re-exports, each with the text of its import and the
 *     name it is exported under
 * @param generatedNames every name the package defines at top level, sorted, which custom code must
 *     not define too
 * @param hasExample whether there is an operation for the README example to call
 * @param exampleAttribute the client attribute of the example's resource, or {@code ""}
 * @param exampleMethod the example's method name, or {@code ""}
 * @param exampleArgs the example's required arguments as placeholders, or {@code ""}
 */
record PyClientView(
    String className,
    String asyncClassName,
    String title,
    String docTitle,
    String packageDir,
    String packageName,
    String packageNameLiteral,
    String versionLiteral,
    String descriptionLiteral,
    String defaultBaseUrlLiteral,
    String defaultBaseUrl,
    boolean hasDefaultBaseUrl,
    String baseUrlAdvice,
    String baseUrlErrorLiteral,
    String maxRetriesErrorLiteral,
    String asyncBaseUrlErrorLiteral,
    String asyncMaxRetriesErrorLiteral,
    boolean hasApiKey,
    boolean hasBearer,
    boolean hasBasic,
    List<Literal> schemeLocations,
    List<Resource> resources,
    List<Export> modelNames,
    boolean hasModelNames,
    List<Literal> generatedNames,
    boolean hasExample,
    String exampleAttribute,
    String exampleMethod,
    String exampleArgs) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  /** Client attributes a resource must not take: they are the client's own methods. */
  private static final Set<String> RESERVED_ATTRIBUTES = Set.of("close", "aclose");

  /** A quoted name rendered verbatim into the template. */
  record Literal(String text) {}

  /** A model re-exported from the package: its import text, and the name it is exported under. */
  record Export(String importText, String exportName) {}

  /**
   * One resource module: {@code attribute} holds it on each client, and {@code module} is the
   * module file name under {@code resources/}.
   */
  record Resource(String attribute, String module, String className, String asyncClassName) {}

  /**
   * The operation shown in the README: {@code attribute.method(args)} on a resource, with
   * placeholder values for its required arguments.
   */
  record Example(String attribute, String method, String args) {}

  /**
   * @param modelNames the names of the models module, which the package re-exports; a name that
   *     collides with one of the package's own is exported under an alias
   */
  static PyClientView of(ApiModel model, GeneratorContext ctx, List<String> modelNames) {
    String pypiName = ctx.names().pypiName();
    if (pypiName == null || pypiName.isBlank()) {
      throw new IllegalArgumentException(
          "PackageNames.pypiName is required to generate the Python SDK");
    }
    NameStrategy names = NameStrategy.forLanguage(Language.PYTHON);
    String className = clientName(names.type(model.info().title()));
    String title = plainText(model.info().title());
    String packageName = pypiName.strip();
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    String defaultUrl = defaultBaseUrl(model.servers());
    String advice = baseUrlAdvice(model.servers());
    boolean hasApiKey = schemes.stream().anyMatch(s -> apiKeyLocation(s) != null);
    List<Literal> locations = schemeLocations(model.securitySchemes());
    String asyncName = "Async" + className;
    // Every name the package defines at top level. A model that collides with one is exported
    // under an alias, so that neither name is replaced silently.
    Set<String> taken = new HashSet<>(List.of(className, asyncName));
    taken.addAll(ERROR_EXPORTS);
    List<Export> exports = new ArrayList<>();
    for (String name : modelNames) {
      if (taken.contains(name)) {
        String alias = PyTypeRenderer.unique(name + "Model", taken);
        exports.add(new Export(name + " as " + alias, alias));
      } else {
        taken.add(name);
        exports.add(new Export(name, name));
      }
    }
    List<Literal> generatedNames = taken.stream().sorted().map(n -> new Literal(n)).toList();
    List<Resource> resources = resources(model);
    Example example = example(model, names, resources);
    return new PyClientView(
        className,
        "Async" + className,
        title,
        docText(model.info().title()),
        names.file(packageName),
        packageName,
        quote(packageName),
        quote(ctx.version()),
        quote("Python client for the " + title + " API"),
        quote(defaultUrl),
        defaultUrl,
        !defaultUrl.isEmpty(),
        advice,
        quote(className + ": base_url is required. " + advice),
        quote(className + ": max_retries must be a non-negative integer"),
        quote("Async" + className + ": base_url is required. " + advice),
        quote("Async" + className + ": max_retries must be a non-negative integer"),
        hasApiKey,
        schemes.stream().anyMatch(PyClientView::isBearerScheme),
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme())),
        locations,
        resources,
        exports,
        !exports.isEmpty(),
        generatedNames,
        example != null,
        example == null ? "" : example.attribute(),
        example == null ? "" : example.method(),
        example == null ? "" : example.args());
  }

  /** The error classes the package re-exports from {@code errors.py}. */
  private static final List<String> ERROR_EXPORTS =
      List.of(
          "ApiError",
          "BadRequestError",
          "ConflictError",
          "ForbiddenError",
          "NotFoundError",
          "RateLimitError",
          "ServerError",
          "UnauthorizedError");

  /**
   * One resource per operation module, in model order. The attribute is the tag's member name, made
   * unique among the names the client already uses. Tags that share a module name share one
   * resource, named by the first of them.
   */
  static List<Resource> resources(ApiModel model) {
    NameStrategy names = NameStrategy.forLanguage(Language.PYTHON);
    Map<String, String> firstTagOfModule = new TreeMap<>();
    for (Operation op : model.operations()) {
      firstTagOfModule.putIfAbsent(names.file(op.tag()), op.tag());
    }
    Set<String> taken = new HashSet<>(RESERVED_ATTRIBUTES);
    List<Resource> resources = new ArrayList<>();
    for (var entry : firstTagOfModule.entrySet()) {
      String tag = entry.getValue();
      String className = names.type(tag) + "Resource";
      String attribute = PyTypeRenderer.unique(names.member(tag), taken);
      resources.add(new Resource(attribute, entry.getKey(), className, "Async" + className));
    }
    return List.copyOf(resources);
  }

  /**
   * The README example: the first operation with no required argument, else the first operation.
   * Null when the API has no operations.
   */
  static Example example(ApiModel model, NameStrategy names, List<Resource> resources) {
    Operation chosen = null;
    for (Operation op : model.operations()) {
      if (requiredArgs(op, names).isEmpty()) {
        chosen = op;
        break;
      }
    }
    if (chosen == null) {
      if (model.operations().isEmpty()) {
        return null;
      }
      chosen = model.operations().get(0);
    }
    String module = names.file(chosen.tag());
    String attribute =
        resources.stream()
            .filter(r -> r.module().equals(module))
            .findFirst()
            .orElseThrow()
            .attribute();
    String args =
        String.join(", ", requiredArgs(chosen, names).stream().map(a -> a + "=...").toList());
    return new Example(attribute, names.member(chosen.operationId()), args);
  }

  /** The names of an operation's required arguments, in the order the method takes them. */
  private static List<String> requiredArgs(Operation op, NameStrategy names) {
    List<String> args = new ArrayList<>();
    for (Parameter p : op.parameters()) {
      if (p.in() == ParamLocation.PATH || p.required()) {
        args.add(names.member(p.name()));
      }
    }
    if (op.requestBody() != null && op.requestBody().required()) {
      args.add("body");
    }
    return args;
  }

  /** HTTP bearer, OAuth2 and OpenID Connect schemes all carry an access token as a bearer. */
  private static boolean isBearerScheme(SecurityScheme scheme) {
    return switch (scheme.kind()) {
      case HTTP -> "bearer".equalsIgnoreCase(scheme.scheme());
      case OAUTH2, OPENID_CONNECT -> true;
      case API_KEY -> false;
    };
  }

  /**
   * The first server when it is an absolute http(s) URL without template variables, else empty. The
   * parser fills in a relative {@code /} server when the document declares none, and a relative URL
   * cannot be the base of an SDK request, so only absolute URLs become the default.
   */
  static String defaultBaseUrl(List<Server> servers) {
    if (servers.isEmpty() || !ABSOLUTE_HTTP_URL.matcher(servers.get(0).url()).matches()) {
      return "";
    }
    return servers.get(0).url();
  }

  /**
   * What the caller must pass when there is no default base URL. A relative or templated first
   * server is named in the sentence, with an example of a full URL.
   */
  static String baseUrlAdvice(List<Server> servers) {
    if (!defaultBaseUrl(servers).isEmpty()) {
      return "Pass a non-empty base URL.";
    }
    if (servers.isEmpty() || servers.get(0).url().equals("/")) {
      return "The API declares no absolute server URL. Pass the full base URL, e.g. "
          + "https://api.example.com.";
    }
    String url = servers.get(0).url();
    boolean templated = url.contains("{");
    String reason = templated ? "has template variables" : "is not an absolute http(s) URL";
    String example = "https://api.example.com" + (url.startsWith("/") && !templated ? url : "");
    return "The first server, "
        + url
        + ", "
        + reason
        + ". Pass the full base URL, e.g. "
        + example
        + ".";
  }

  /** Appends {@code Client} unless the name already ends with it. */
  static String clientName(String typeName) {
    return typeName.endsWith("Client") ? typeName : typeName + "Client";
  }

  /** Collapses whitespace so the title fits on one line. */
  static String plainText(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  /**
   * {@link #plainText} with backslashes and double quotes escaped, so the text cannot end a
   * triple-quoted docstring or start an escape sequence.
   */
  static String docText(String text) {
    return plainText(text).replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Renders {@code value} as a double-quoted string literal. Backslashes, quotes, control
   * characters and the line and paragraph separators are escaped. The escapes are valid in both
   * Python and TOML, so one form serves both templates.
   */
  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /**
   * The table entry, as Python text, for an apiKey scheme that is sent in a header or a query
   * parameter; null for any other scheme.
   */
  private static String apiKeyLocation(SecurityScheme scheme) {
    if (scheme.kind() != Kind.API_KEY || scheme.paramName() == null) {
      return null;
    }
    if ("header".equals(scheme.in())
        || "cookie".equals(scheme.in())
        || "query".equals(scheme.in())) {
      return "(" + quote(scheme.in()) + ", " + quote(scheme.paramName()) + ")";
    }
    return null;
  }

  /**
   * The client's credential table: every scheme whose credential the client can send, with where
   * the credential goes. An apiKey names its header or query parameter; bearer and basic
   * credentials are Authorization headers, so their name is empty.
   */
  static List<Literal> schemeLocations(Map<String, SecurityScheme> schemes) {
    List<Literal> out = new ArrayList<>();
    for (var entry : schemes.entrySet()) {
      SecurityScheme scheme = entry.getValue();
      String location = apiKeyLocation(scheme);
      if (location == null && isBearerScheme(scheme)) {
        location = "(\"bearer\", \"\")";
      } else if (location == null
          && scheme.kind() == Kind.HTTP
          && "basic".equalsIgnoreCase(scheme.scheme())) {
        location = "(\"basic\", \"\")";
      }
      if (location != null) {
        out.add(new Literal(quote(entry.getKey()) + ": " + location));
      }
    }
    return List.copyOf(out);
  }
}
