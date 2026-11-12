package dev.emitkit.gen.typescriptsdk;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Values rendered by the TypeScript SDK templates. Fields named {@code *Literal} are already quoted
 * as string literals valid in both TypeScript and JSON; the others are plain text.
 *
 * @param className client class name, ending in {@code Client} exactly once
 * @param title single-line API title, safe inside a block comment
 * @param packageName npm package name, scoped when the context has a scope
 * @param packageNameLiteral {@code packageName} as a string literal
 * @param versionLiteral package version as a string literal
 * @param defaultBaseUrlLiteral default base URL as a string literal, or {@code ""} when none
 * @param hasApiKey whether the API declares a header or query apiKey scheme
 * @param hasBearer whether the API declares an HTTP bearer scheme
 * @param hasBasic whether the API declares an HTTP basic scheme
 * @param apiKeyHeaders header names that receive the API key
 * @param apiKeyQueries query parameter names that receive the API key, empty when a header apiKey
 *     scheme exists so the key never appears in a URL
 */
record TsClientView(
    String className,
    String title,
    String packageName,
    String packageNameLiteral,
    String versionLiteral,
    String defaultBaseUrlLiteral,
    boolean hasApiKey,
    boolean hasBearer,
    boolean hasBasic,
    List<Literal> apiKeyHeaders,
    List<Literal> apiKeyQueries) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  /** A quoted name rendered verbatim into the template. */
  record Literal(String text) {}

  static TsClientView of(ApiModel model, GeneratorContext ctx) {
    String className =
        clientName(NameStrategy.forLanguage(Language.TYPESCRIPT).type(model.info().title()));
    PackageNames names = ctx.names();
    String scope = names.npmScope();
    String packageName =
        scope == null || scope.isEmpty() ? names.npmName() : "@" + scope + "/" + names.npmName();
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    String baseUrl = defaultBaseUrl(model.servers());
    List<Literal> headers = apiKeyNames(schemes, "header");
    List<Literal> queries = headers.isEmpty() ? apiKeyNames(schemes, "query") : List.of();
    return new TsClientView(
        className,
        docText(model.info().title()),
        packageName,
        quote(packageName),
        quote(ctx.version()),
        quote(baseUrl),
        !headers.isEmpty() || !queries.isEmpty(),
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "bearer".equalsIgnoreCase(s.scheme())),
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme())),
        headers,
        queries);
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

  /** Appends {@code Client} unless the name already ends with it. */
  static String clientName(String typeName) {
    return typeName.endsWith("Client") ? typeName : typeName + "Client";
  }

  /** Collapses whitespace and neutralises {@code *\/} so the text cannot close a block comment. */
  static String docText(String text) {
    return text.replaceAll("\\s+", " ").replace("*/", "*\\/").strip();
  }

  /**
   * Renders {@code value} as a double-quoted string literal. Backslashes, quotes, control
   * characters and the line and paragraph separators are escaped, so no value can end the literal.
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
