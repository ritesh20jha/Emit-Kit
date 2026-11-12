package dev.emitkit.gen.javasdk;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Values rendered by the Java SDK templates. {@code *Literal} fields are quoted for their target:
 * Java string literals, or Kotlin literals for {@code build.gradle.kts}. The other fields are plain
 * text for comments and Markdown.
 */
record JavaClientView(
    String packageName,
    String packagePath,
    String className,
    String title,
    String version,
    String groupId,
    String artifactId,
    String groupLiteral,
    String artifactLiteral,
    String versionLiteral,
    String defaultBaseUrlLiteral,
    String defaultBaseUrl,
    boolean hasDefaultBaseUrl,
    String baseUrlAdvice,
    String baseUrlErrorLiteral,
    boolean hasApiKey,
    boolean hasBearer,
    boolean hasBasic,
    List<Literal> apiKeyHeaders,
    List<Literal> apiKeyQueries) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");
  private static final Pattern JAVA_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** A quoted name rendered verbatim into the template. */
  record Literal(String text) {}

  static JavaClientView of(ApiModel model, GeneratorContext ctx) {
    PackageNames names = ctx.names();
    String groupId = Objects.requireNonNull(names.mavenGroupId(), "mavenGroupId");
    String artifactId = Objects.requireNonNull(names.mavenArtifactId(), "mavenArtifactId");
    String packageName = basePackage(groupId, artifactId);
    String className =
        clientName(NameStrategy.forLanguage(Language.JAVA).type(model.info().title()));
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    String defaultUrl = defaultBaseUrl(model.servers());
    String advice = baseUrlAdvice(model.servers());
    List<Literal> headers = apiKeyNames(schemes, "header");
    List<Literal> queries = headers.isEmpty() ? apiKeyNames(schemes, "query") : List.of();
    return new JavaClientView(
        packageName,
        packageName.replace('.', '/'),
        className,
        docText(model.info().title()),
        ctx.version(),
        groupId,
        artifactId,
        kotlinQuote(groupId),
        kotlinQuote(artifactId),
        kotlinQuote(ctx.version()),
        quote(defaultUrl),
        defaultUrl,
        !defaultUrl.isEmpty(),
        advice,
        quote(className + ": baseUrl is required. " + advice),
        !headers.isEmpty() || !queries.isEmpty(),
        schemes.stream().anyMatch(JavaClientView::isBearerScheme),
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme())),
        headers,
        queries);
  }

  /**
   * The group id followed by the artifact id with every non-alphanumeric removed, lower-cased.
   * Fails when the result is not a legal Java package name, since the package line is written
   * verbatim.
   */
  static String basePackage(String groupId, String artifactId) {
    NameStrategy java = NameStrategy.forLanguage(Language.JAVA);
    String segment = java.member(artifactId).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    String packageName = groupId + "." + segment;
    boolean legal =
        Arrays.stream(packageName.split("\\.", -1))
            .allMatch(part -> JAVA_SEGMENT.matcher(part).matches() && !java.isReserved(part));
    if (!legal) {
      throw new IllegalArgumentException(
          "mavenGroupId and mavenArtifactId do not form a legal Java package name: " + packageName);
    }
    return packageName;
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

  /**
   * Collapses whitespace and neutralises text that would end a block comment. {@code *}{@code /}
   * becomes {@code *&#47;}. Backslashes become {@code &#92;}, because javac translates {@code
   * \\uXXXX} before it lexes comments, so {@code \\u002a/} would otherwise close the comment.
   */
  static String docText(String text) {
    return text.replaceAll("\\s+", " ").replace("\\", "&#92;").replace("*/", "*&#47;").strip();
  }

  /**
   * Renders {@code value} as a double-quoted Java string literal. Quotes, backslashes and line
   * breaks are escaped. Control characters become octal escapes and non-ASCII becomes {@code
   * \\uXXXX}, because javac translates unicode escapes before lexing, so a {@code \\u000a} could
   * break the literal. Escaped text never contains a bare quote or backslash.
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
          if (c < 0x20) {
            out.append(String.format("\\%03o", (int) c));
          } else if (c >= 0x7F) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /** Renders {@code value} as a double-quoted Kotlin string literal, escaping {@code $}. */
  static String kotlinQuote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '$' -> out.append("\\$");
        default -> {
          if (c < 0x20 || c >= 0x7F) {
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
