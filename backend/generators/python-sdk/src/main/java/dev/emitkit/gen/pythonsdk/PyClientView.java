package dev.emitkit.gen.pythonsdk;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.List;
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
 * @param maxRetriesErrorLiteral the constructor's retry validation message as a string literal
 * @param hasApiKey whether the API declares a header or query apiKey scheme
 * @param hasBearer whether the API declares an HTTP bearer, OAuth2 or OpenID Connect scheme
 * @param hasBasic whether the API declares an HTTP basic scheme
 * @param apiKeyHeaders header names that receive the API key
 * @param apiKeyQueries query parameter names that receive the API key, empty when a header apiKey
 *     scheme exists so the key never appears in a URL
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
    boolean hasApiKey,
    boolean hasBearer,
    boolean hasBasic,
    List<Literal> apiKeyHeaders,
    List<Literal> apiKeyQueries) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  /** A quoted name rendered verbatim into the template. */
  record Literal(String text) {}

  static PyClientView of(ApiModel model, GeneratorContext ctx) {
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
    List<Literal> headers = apiKeyNames(schemes, "header");
    List<Literal> queries = headers.isEmpty() ? apiKeyNames(schemes, "query") : List.of();
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
        !headers.isEmpty() || !queries.isEmpty(),
        schemes.stream().anyMatch(PyClientView::isBearerScheme),
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme())),
        headers,
        queries);
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
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  private static List<Literal> apiKeyNames(List<SecurityScheme> schemes, String location) {
    return schemes.stream()
        .filter(s -> s.kind() == Kind.API_KEY && location.equals(s.in()) && s.paramName() != null)
        .map(s -> new Literal(quote(s.paramName())))
        .toList();
  }
}
