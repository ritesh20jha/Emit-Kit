package dev.emitkit.gen.mcp;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Values rendered by the MCP templates. Fields named {@code *Literal} or {@code *Json} are already
 * valid TypeScript, the others are plain text.
 *
 * @param title single-line API title for the README heading
 * @param packageNameLiteral npm package name, {@code <base>-mcp} with the scope when there is one
 * @param binLiteral executable name, {@code <base>-mcp}, which keeps it apart from the CLI's bin
 * @param serverNameLiteral the name the server reports: the SDK-style package name, {@code <base>}
 * @param versionLiteral package version as a string literal
 * @param defaultBaseUrl the first server when it is an absolute http(s) URL, else empty
 * @param defaultBaseUrlLiteral {@code defaultBaseUrl} as a string literal
 * @param hasDefaultBaseUrl whether {@code defaultBaseUrl} is set
 * @param securitySchemesJson each scheme's credential as {@code [name, credential]} pairs, as JSON
 * @param toolsJson the tool list as a JSON array
 */
record McpView(
    String title,
    String packageNameLiteral,
    String binLiteral,
    String serverNameLiteral,
    String versionLiteral,
    String defaultBaseUrl,
    String defaultBaseUrlLiteral,
    boolean hasDefaultBaseUrl,
    String securitySchemesJson,
    String toolsJson) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  static McpView of(ApiModel model, GeneratorContext ctx, List<McpGenerator.Tool> tools) {
    PackageNames names = ctx.names();
    String scope = names.npmScope();
    String prefix = scope == null || scope.isEmpty() ? "" : "@" + scope + "/";
    String defaultUrl = defaultBaseUrl(model.servers());
    return new McpView(
        McpGenerator.oneLine(model.info().title()),
        Json.quote(prefix + names.npmName() + "-mcp"),
        Json.quote(names.npmName() + "-mcp"),
        Json.quote(prefix + names.npmName()),
        Json.quote(ctx.version()),
        defaultUrl,
        Json.quote(defaultUrl),
        !defaultUrl.isEmpty(),
        securitySchemesJson(model.securitySchemes().values()),
        Json.write(tools.stream().map(McpGenerator.Tool::asMap).toList()));
  }

  /**
   * The first server when it is an absolute http(s) URL without template variables, else empty. A
   * relative URL cannot be the base of a request, so only absolute URLs become the default.
   */
  static String defaultBaseUrl(List<Server> servers) {
    if (servers.isEmpty() || !ABSOLUTE_HTTP_URL.matcher(servers.get(0).url()).matches()) {
      return "";
    }
    return servers.get(0).url();
  }

  /**
   * The {@code [name, credential]} pairs as JSON, one per scheme that a tool can send a credential
   * for. Schemes the server cannot fill, such as HTTP basic, are left out.
   */
  static String securitySchemesJson(Collection<SecurityScheme> schemes) {
    List<List<Object>> entries = new ArrayList<>();
    for (SecurityScheme scheme : schemes) {
      Map<String, Object> credential = credential(scheme);
      if (credential != null) {
        entries.add(List.of(scheme.name(), credential));
      }
    }
    return Json.write(entries);
  }

  private static final Map<String, Object> BEARER = Map.of("kind", "bearer");

  /** The credential for a scheme, or null when no environment variable fills it. */
  private static @Nullable Map<String, Object> credential(SecurityScheme scheme) {
    return switch (scheme.kind()) {
      case OAUTH2, OPENID_CONNECT -> BEARER;
      case HTTP -> "bearer".equalsIgnoreCase(scheme.scheme()) ? BEARER : null;
      case API_KEY -> apiKeyCredential(scheme);
    };
  }

  private static @Nullable Map<String, Object> apiKeyCredential(SecurityScheme scheme) {
    String in = scheme.in();
    boolean supported = "header".equals(in) || "query".equals(in) || "cookie".equals(in);
    if (!supported || scheme.paramName() == null) {
      return null;
    }
    Map<String, Object> credential = new LinkedHashMap<>();
    credential.put("kind", "apiKey");
    credential.put("in", in);
    credential.put("name", scheme.paramName());
    return credential;
  }
}
