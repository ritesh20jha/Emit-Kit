package dev.emitkit.gen.mcp;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Values rendered by the MCP templates. Fields named {@code *Literal} or {@code *Json} are already
 * valid TypeScript, the others are plain text.
 *
 * @param title single-line API title for the README heading
 * @param packageNameLiteral npm package name as a string literal, also the MCP server name
 * @param versionLiteral package version as a string literal
 * @param securitySchemesJson each scheme's credential as {@code [name, credential]} pairs, as JSON
 * @param toolsJson the tool list as a JSON array
 */
record McpView(
    String title,
    String packageNameLiteral,
    String versionLiteral,
    String securitySchemesJson,
    String toolsJson) {

  static McpView of(ApiModel model, GeneratorContext ctx, List<McpGenerator.Tool> tools) {
    var names = ctx.names();
    String scope = names.npmScope();
    String packageName =
        scope == null || scope.isEmpty() ? names.npmName() : "@" + scope + "/" + names.npmName();
    return new McpView(
        McpGenerator.oneLine(model.info().title()),
        Json.quote(packageName),
        Json.quote(ctx.version()),
        securitySchemesJson(model.securitySchemes().values()),
        Json.write(tools.stream().map(McpGenerator.Tool::asMap).toList()));
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
