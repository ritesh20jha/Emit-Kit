package dev.emitkit.gen.docs;

import dev.emitkit.gen.core.DocsExamples;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.RequestBody;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** Template code examples for one operation: curl, TypeScript, Python and Java. */
final class Examples {

  /** The base URL used when the spec declares no absolute server. */
  static final String DEFAULT_BASE_URL = "https://api.example.com";

  private static final Templates TEMPLATES = Templates.forGenerator("docs");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Values the templates interpolate. Each literal is quoted for the language that uses it. */
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

  static DocsExamples render(ApiModel model, Operation op) {
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
            literal(url, '\''),
            literal(url, '"'),
            shell(json),
            literal(json, '\''),
            literal(json, '"'));
    return new DocsExamples(
        snippet("curl", request),
        snippet("typescript", request),
        snippet("python", request),
        snippet("java", request),
        "");
  }

  private static String snippet(String name, Request request) {
    return TEMPLATES.render("examples/" + name, request).stripTrailing();
  }

  /** The first server without a trailing slash; a relative one is appended to the placeholder. */
  private static String baseUrl(ApiModel model) {
    String url =
        model.servers().isEmpty() ? "" : model.servers().getFirst().url().replaceAll("/+$", "");
    return url.matches("https?://.*") ? url : DEFAULT_BASE_URL + url;
  }

  /** The path with each {@code {name}} replaced by a sample, then the required query string. */
  private static String path(ApiModel model, Operation op) {
    Matcher matcher = PLACEHOLDER.matcher(op.path());
    var out = new StringBuffer();
    while (matcher.find()) {
      String value = sampleText(op, model, ParamLocation.PATH, matcher.group(1));
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    String query =
        op.parameters().stream()
            .filter(p -> p.in() == ParamLocation.QUERY && p.required())
            .map(p -> p.name() + "=" + sampleText(op, model, p.in(), p.name()))
            .collect(Collectors.joining("&"));
    return out + (query.isEmpty() ? "" : "?" + query);
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
  private static String shell(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  /** A string literal delimited by {@code quote}: single quotes for TypeScript and Python. */
  private static String literal(String text, char quote) {
    return quote + text.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote) + quote;
  }
}
