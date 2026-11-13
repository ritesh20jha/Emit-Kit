package dev.emitkit.gen.docs;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import java.util.List;

/**
 * Values rendered by {@code index.md.mustache}: the API title and description, links to the tag
 * pages, and the declared security schemes.
 *
 * @param title one-line API title
 * @param hasDescription whether {@code description} is shown
 * @param description the API description, if it has one
 * @param tags one link per tag page
 * @param hasSchemes whether any security scheme is declared
 * @param schemes the declared security schemes
 */
record IndexView(
    String title,
    boolean hasDescription,
    String description,
    List<Link> tags,
    boolean hasSchemes,
    List<Scheme> schemes) {

  record Link(String name, String file, String operations) {}

  record Scheme(String name, String type, String details) {}

  static IndexView of(ApiModel model, List<DocsGenerator.TagGroup> groups) {
    String description = model.info().description();
    boolean hasDescription = description != null && !description.isBlank();
    List<Scheme> schemes =
        model.securitySchemes().values().stream().map(IndexView::scheme).toList();
    return new IndexView(
        Markdown.cell(model.info().title()),
        hasDescription,
        hasDescription ? description.strip() : "",
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
