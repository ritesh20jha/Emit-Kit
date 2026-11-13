package dev.emitkit.gen.docs;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import java.util.List;

/**
 * Values rendered by {@code index.md.mustache}. Servers are pre-formatted lines; cells are escaped.
 */
record IndexView(
    String title,
    String version,
    boolean hasDescription,
    String description,
    boolean hasServers,
    List<String> servers,
    List<Link> tags,
    boolean hasSchemes,
    List<Scheme> schemes) {

  record Link(String name, String file, String operations) {}

  record Scheme(String name, String type, String details) {}

  static IndexView of(ApiModel model, List<DocsGenerator.TagGroup> groups) {
    String description = model.info().description();
    boolean hasDescription = description != null && !description.isBlank();
    var servers =
        model.servers().stream().map(s -> "`" + s.url() + "`" + suffix(s.description())).toList();
    List<Scheme> schemes =
        model.securitySchemes().values().stream().map(IndexView::scheme).toList();
    return new IndexView(
        DocsView.cell(model.info().title()),
        DocsView.cell(model.info().version()),
        hasDescription,
        hasDescription ? description.strip() : "",
        !servers.isEmpty(),
        servers,
        groups.stream()
            .map(g ->
                new Link(
                    DocsView.cell(g.tag().name()),
                    g.file(),
                    g.ops().size() + (g.ops().size() == 1 ? " operation" : " operations")))
            .toList(),
        !schemes.isEmpty(),
        schemes);
  }

  private static String suffix(String description) {
    return description == null || description.isBlank() ? "" : ": " + DocsView.cell(description);
  }

  private static Scheme scheme(SecurityScheme s) {
    return new Scheme(DocsView.cell(s.name()), DocsView.cell(type(s)), DocsView.cell(details(s)));
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
