package dev.emitkit.gen.mcp;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import java.util.List;

/**
 * Values rendered by the MCP templates. Fields named {@code *Literal} or {@code *Json} are already
 * valid TypeScript, the others are plain text.
 *
 * @param title single-line API title for the README heading
 * @param packageNameLiteral npm package name as a string literal, also the MCP server name
 * @param versionLiteral package version as a string literal
 * @param apiKeyHeadersJson header names that receive the API key, as a JSON array
 * @param apiKeyQueriesJson query parameter names that receive the API key, as a JSON array; empty
 *     when a header apiKey scheme exists so the key never appears in a URL
 * @param toolsJson the tool list as a JSON array
 */
record McpView(
    String title,
    String packageNameLiteral,
    String versionLiteral,
    String apiKeyHeadersJson,
    String apiKeyQueriesJson,
    String toolsJson) {

  static McpView of(ApiModel model, GeneratorContext ctx, List<McpGenerator.Tool> tools) {
    var names = ctx.names();
    String scope = names.npmScope();
    String packageName =
        scope == null || scope.isEmpty() ? names.npmName() : "@" + scope + "/" + names.npmName();
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    List<String> headers = apiKeyNames(schemes, "header");
    List<String> queries = headers.isEmpty() ? apiKeyNames(schemes, "query") : List.of();
    return new McpView(
        McpGenerator.oneLine(model.info().title()),
        Json.quote(packageName),
        Json.quote(ctx.version()),
        Json.write(headers),
        Json.write(queries),
        Json.write(tools.stream().map(McpGenerator.Tool::asMap).toList()));
  }

  private static List<String> apiKeyNames(List<SecurityScheme> schemes, String location) {
    return schemes.stream()
        .filter(s -> s.kind() == Kind.API_KEY && location.equals(s.in()) && s.paramName() != null)
        .map(SecurityScheme::paramName)
        .toList();
  }
}
