package dev.emitkit.gen.docs;

import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * Template code examples for one operation: curl, TypeScript, Python and Java. Each snippet calls
 * the operation with sample values, and a request body is a sample built from its schema.
 */
final class Examples {

  /** The base URL used when the spec declares no server. */
  static final String DEFAULT_BASE_URL = "https://api.example.com";

  private static final Templates TEMPLATES = Templates.forGenerator("docs");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Rendered snippets, without code fences. */
  record Snippets(String curl, String typescript, String python, String java) {}

  /**
   * Values the example templates interpolate. Each literal is quoted for its language: {@code
   * *Shell} for a single-quoted shell word, {@code *Js} for a single-quoted string literal (used by
   * TypeScript and Python), {@code *Java} for a double-quoted Java string.
   */
  record Request(
      String method,
      String contentType,
      boolean hasBody,
      String urlShell,
      String urlJs,
      String urlJava,
      String bodyShell,
      String bodyJs,
      String bodyJava) {}

  private Examples() {}

  static Snippets render(ApiModel model, Operation op) {
    String url = baseUrl(model) + path(model, op);
    RequestBody body = op.requestBody();
    String json =
        body == null ? "" : JSON.writeValueAsString(Schemas.sample(body.schema(), model.schemas()));
    var request =
        new Request(
            op.method(),
            body == null ? "" : body.contentType(),
            body != null,
            shell(url),
            js(url),
            java(url),
            shell(json),
            js(json),
            java(json));
    return new Snippets(
        snippet("curl", request),
        snippet("typescript", request),
        snippet("python", request),
        snippet("java", request));
  }

  private static String snippet(String name, Request request) {
    return TEMPLATES.render("examples/" + name, request).stripTrailing();
  }

  /**
   * The first server without a trailing slash. A relative server, such as the {@code /} the parser
   * gives an operation with no servers, is appended to the placeholder host.
   */
  private static String baseUrl(ApiModel model) {
    String url =
        model.servers().isEmpty() ? "" : model.servers().getFirst().url().replaceAll("/+$", "");
    return url.matches("https?://.*") ? url : DEFAULT_BASE_URL + url;
  }

  /** The path with each {@code {name}} replaced by a sample, then the required query string. */
  private static String path(ApiModel model, Operation op) {
    Matcher matcher = PLACEHOLDER.matcher(op.path());
    var path = new StringBuffer();
    while (matcher.find()) {
      String value = sampleText(op, model, ParamLocation.PATH, matcher.group(1));
      matcher.appendReplacement(path, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(path);
    var query = new StringBuilder();
    for (Parameter p : op.parameters()) {
      if (p.in() == ParamLocation.QUERY && p.required()) {
        query.append(query.isEmpty() ? '?' : '&');
        query.append(p.name()).append('=').append(sampleText(op, model, p.in(), p.name()));
      }
    }
    return path + query.toString();
  }

  /** A sample value for the named parameter, or the name itself when the operation lacks it. */
  private static String sampleText(Operation op, ApiModel model, ParamLocation in, String name) {
    return op.parameters().stream()
        .filter(p -> p.in() == in && p.name().equals(name))
        .findFirst()
        .map(p -> String.valueOf(Schemas.sample(p.schema(), model.schemas())))
        .orElse(name);
  }

  /** A single-quoted shell word. */
  static String shell(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  /** A single-quoted TypeScript or Python string literal. */
  static String js(String text) {
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  /** A double-quoted Java string literal. */
  static String java(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
