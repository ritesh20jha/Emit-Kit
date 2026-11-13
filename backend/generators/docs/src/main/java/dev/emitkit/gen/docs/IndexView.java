package dev.emitkit.gen.docs;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import java.util.List;

/**
 * Values rendered by {@code index.md.mustache}: the API title, version and description, the
 * declared servers, a table of tag pages, and the declared security schemes.
 *
 * @param title one-line API title
 * @param version one-line API version
 * @param hasDescription whether {@code description} is shown
 * @param description the API description, if it has one
 * @param hasServers whether any server is declared
 * @param servers one line per declared server
 * @param tags one row per tag page
 * @param hasSchemes whether any security scheme is declared
 * @param schemes the declared security schemes
 */
record IndexView(
    String title,
    String version,
    boolean hasDescription,
    String description,
    boolean hasServers,
    List<Line> servers,
    List<Link> tags,
    boolean hasSchemes,
    List<Scheme> schemes) {

  /** A pre-formatted line, such as a server. */
  record Line(String text) {}

  record Link(String name, String file, String operations) {}

  record Scheme(String name, String type, String details) {}

  static IndexView of(ApiModel model, List<DocsGenerator.TagGroup> groups) {
    String description = model.info().description();
    boolean hasDescription = description != null && !description.isBlank();
    var servers =
        model.servers().stream()
            .map(s ->
                new Line(
                    "`"
                        + s.url()
                        + "`"
                        + (s.description() == null || s.description().isBlank()
                            ? ""
                            : ": " + Markdown.cell(s.description()))))
            .toList();
    List<Scheme> schemes =
        model.securitySchemes().values().stream().map(IndexView::scheme).toList();
    return new IndexView(
        Markdown.cell(model.info().title()),
        Markdown.cell(model.info().version()),
        hasDescription,
        hasDescription ? description.strip() : "",
        !servers.isEmpty(),
        servers,
        groups.stream()
            .map(g ->
                new Link(
                    Markdown.cell(g.tag().name()),
                    g.file(),
                    g.ops().size() + (g.ops().size() == 1 ? " operation" : " operations")))
            .toList(),
        !schemes.isEmpty(),
        schemes);
  }

  private static Scheme scheme(SecurityScheme s) {
    return new Scheme(Markdown.cell(s.name()), Markdown.cell(type(s)), Markdown.cell(details(s)));
  }

  private static String type(SecurityScheme s) {
    return switch (s.kind()) {
      case API_KEY -> "API key";
      case HTTP -> "HTTP";
      case OAUTH2 -> "OAuth 2.0";
      case OPENID_CONNECT -> "OpenID Connect";
    };
  }

  private static String details(SecurityScheme s) {
    return switch (s.kind()) {
      case API_KEY ->
          s.in() == null || s.paramName() == null ? "" : s.in() + " `" + s.paramName() + "`";
      case HTTP -> s.scheme() == null ? "" : "scheme `" + s.scheme() + "`";
      case OAUTH2, OPENID_CONNECT -> "";
    };
  }
}
