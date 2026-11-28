package dev.emitkit.gen.javasdk;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Values rendered by the Java SDK templates. {@code *Literal} fields are Java string literals.
 * Coordinates are validated to Kotlin-safe characters, so templates place them raw.
 */
record JavaClientView(
    String packageName,
    String packagePath,
    String className,
    String title,
    String version,
    String groupId,
    String artifactId,
    String defaultBaseUrlLiteral,
    String defaultBaseUrl,
    boolean hasDefaultBaseUrl,
    String baseUrlAdvice,
    String baseUrlErrorLiteral,
    boolean hasApiKey,
    boolean hasBearer,
    boolean hasBasic,
    /** Statements that put each API key scheme's credential into the builder's map. */
    List<Literal> apiKeyCredentials,
    List<Literal> bearerCredentials,
    List<Literal> basicCredentials,
    List<Accessor> accessors) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");
  private static final Pattern JAVA_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9._+-]+");

  /** A quoted name rendered verbatim into the template. */
  record Literal(String text) {}

  /** A client method returning one resource: its name, its class, and the tags it groups. */
  record Accessor(String member, String resource, String tags) {}

  static JavaClientView of(ApiModel model, GeneratorContext ctx) {
    PackageNames names = ctx.names();
    String groupId = Objects.requireNonNull(names.mavenGroupId(), "mavenGroupId");
    String artifactId =
        coordinate(
            Objects.requireNonNull(names.mavenArtifactId(), "mavenArtifactId"), "artifactId");
    String version = coordinate(ctx.version(), "version");
    String packageName = basePackage(groupId, artifactId);
    String className =
        clientName(NameStrategy.forLanguage(Language.JAVA).type(model.info().title()));
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    String defaultUrl = defaultBaseUrl(model.servers());
    String advice = baseUrlAdvice(model.servers());
    List<Literal> apiKeys = apiKeyCredentials(schemes);
    boolean hasBearer = schemes.stream().anyMatch(JavaClientView::isBearerScheme);
    List<Accessor> accessors =
        JavaResourceRenderer.groups(model).stream()
            .map(g ->
                new Accessor(
                    accessorName(g.member()), g.className(), docText(String.join(", ", g.tags()))))
            .sorted(Comparator.comparing(Accessor::resource))
            .toList();
    return new JavaClientView(
        packageName,
        packageName.replace('.', '/'),
        className,
        docText(model.info().title()),
        version,
        groupId,
        artifactId,
        quote(defaultUrl),
        defaultUrl,
        !defaultUrl.isEmpty(),
        advice,
        quote(className + ": baseUrl is required. " + advice),
        !apiKeys.isEmpty(),
        hasBearer,
        schemes.stream()
            .anyMatch(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme())),
        apiKeys,
        bearerCredentials(schemes),
        basicCredentials(schemes),
        accessors);
  }

  /** A client method name that Object, builder() or mapper() already declares gets a _. */
  static String accessorName(String member) {
    boolean taken =
        JavaTypeRenderer.OBJECT_METHODS.contains(member)
            || member.equals("builder")
            || member.equals("mapper");
    return taken ? member + "_" : member;
  }

  /** Rejects a Maven artifact id or version that could not be written raw into a Kotlin string. */
  static String coordinate(String value, String field) {
    if (!COORDINATE.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " may only contain letters, digits, '.', '_', '+' and '-': " + value);
    }
    return value;
  }

  /** Group id plus artifact id with non-alphanumerics removed. Fails when not a legal package. */
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

  /** The first server when it is an absolute http(s) URL without template variables, else empty. */
  static String defaultBaseUrl(List<Server> servers) {
    if (servers.isEmpty() || !ABSOLUTE_HTTP_URL.matcher(servers.get(0).url()).matches()) {
      return "";
    }
    return servers.get(0).url();
  }

  /** What the caller must pass when there is no default base URL. */
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
   * Text for a javadoc comment, on one line: whitespace collapsed, HTML markup escaped, comment
   * closers and unicode escapes neutralised, and a leading {@code @} kept from starting a tag.
   */
  static String docText(String text) {
    String escaped =
        text.replaceAll("\\s+", " ")
            .strip()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("{@", "&#123;@")
            .replace("\\", "&#92;")
            .replace("*/", "*&#47;");
    return escaped.startsWith("@") ? "&#64;" + escaped.substring(1) : escaped;
  }

  /** Renders {@code value} as a Java string literal, escaping for javac's unicode translation. */
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

  /**
   * Whether an apiKey scheme can send its key: it must name a header, cookie or query parameter.
   */
  static boolean isSendableApiKey(SecurityScheme s) {
    return s != null
        && s.kind() == Kind.API_KEY
        && s.paramName() != null
        && ("header".equals(s.in()) || "cookie".equals(s.in()) || "query".equals(s.in()));
  }

  /**
   * One statement per sendable API key scheme. Each carries the builder's single API key in the
   * scheme's own location and name.
   */
  private static List<Literal> apiKeyCredentials(List<SecurityScheme> schemes) {
    var out = new ArrayList<Literal>();
    for (SecurityScheme s : schemes) {
      if (!isSendableApiKey(s)) {
        continue;
      }
      String entry = quote(s.paramName()) + ", apiKey)";
      String none = "Map.of()";
      out.add(
          new Literal(
              switch (s.in()) {
                case "header" -> putCredential(s.name(), "Map.of(" + entry, none, none);
                case "query" -> putCredential(s.name(), none, "Map.of(" + entry, none);
                default -> putCredential(s.name(), none, none, "Map.of(" + entry);
              }));
    }
    return out;
  }

  /**
   * One statement per bearer scheme: OAuth2 and OpenID Connect carry the token as a bearer. A
   * bearer scheme is registered whenever a token is set, and the request decides whether it is
   * sent.
   */
  private static List<Literal> bearerCredentials(List<SecurityScheme> schemes) {
    return schemes.stream()
        .filter(JavaClientView::isBearerScheme)
        .map(s ->
            new Literal(
                putCredential(
                    s.name(),
                    "Map.of(\"Authorization\", \"Bearer \" + bearerToken)",
                    "Map.of()",
                    "Map.of()")))
        .toList();
  }

  /** One statement per HTTP basic scheme, registered whenever basic credentials are set. */
  private static List<Literal> basicCredentials(List<SecurityScheme> schemes) {
    return schemes.stream()
        .filter(s -> s.kind() == Kind.HTTP && "basic".equalsIgnoreCase(s.scheme()))
        .map(s ->
            new Literal(
                putCredential(
                    s.name(),
                    "Map.of(\"Authorization\", basicCredentials(basicUsername, basicPassword))",
                    "Map.of()",
                    "Map.of()")))
        .toList();
  }

  /** A statement that registers one scheme's headers, query parameters and cookies. */
  private static String putCredential(String scheme, String headers, String query, String cookies) {
    return "credentials.put(\n            "
        + quote(scheme)
        + ",\n            new ClientConfig.Credential("
        + headers
        + ", "
        + query
        + ", "
        + cookies
        + "));";
  }
}
