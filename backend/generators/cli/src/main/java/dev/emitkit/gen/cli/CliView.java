package dev.emitkit.gen.cli;

import static dev.emitkit.gen.cli.TsLiterals.oneLine;
import static dev.emitkit.gen.cli.TsLiterals.quote;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.SecurityScheme.Kind;
import dev.emitkit.spec.model.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Values rendered by the CLI's index.ts, package.json, tsconfig.json and README templates. */
record CliView(
    String title,
    String titleLiteral,
    String binName,
    String binLiteral,
    String packageName,
    String packageNameLiteral,
    String versionLiteral,
    String defaultBaseUrl,
    String defaultBaseUrlLiteral,
    boolean hasDefaultBaseUrl,
    String apiKeyHeadersLiteral,
    String apiKeyQueriesLiteral,
    List<CommandGroup> groups,
    List<CommandLine> commands) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  /** One README entry: the full command line a user types, such as {@code petstore pets}. */
  record CommandLine(String text) {}

  static CliView of(ApiModel model, GeneratorContext ctx) {
    PackageNames names = ctx.names();
    String scope = names.npmScope();
    String packageName =
        scope == null || scope.isEmpty() ? names.npmName() : "@" + scope + "/" + names.npmName();
    String defaultUrl = defaultBaseUrl(model.servers());
    List<SecurityScheme> schemes = List.copyOf(model.securitySchemes().values());
    List<String> headers = apiKeyNames(schemes, "header");
    List<String> queries = headers.isEmpty() ? apiKeyNames(schemes, "query") : List.of();
    if (headers.isEmpty() && queries.isEmpty()) {
      // No API key scheme is declared, so --api-key falls back to the conventional header.
      headers = List.of(quote("X-API-Key"));
    }
    List<CommandGroup> groups = CommandGroup.of(model);
    List<CommandLine> commands = new ArrayList<>();
    for (CommandGroup group : groups) {
      for (CommandOperation op : group.operations()) {
        commands.add(new CommandLine(names.npmName() + " " + group.name() + " " + op.name()));
      }
    }
    String title = model.info().title();
    return new CliView(
        oneLine(title),
        quote(oneLine(title)),
        names.npmName(),
        quote(names.npmName()),
        packageName,
        quote(packageName),
        quote(ctx.version()),
        defaultUrl,
        quote(defaultUrl),
        !defaultUrl.isEmpty(),
        "[" + String.join(", ", headers) + "]",
        "[" + String.join(", ", queries) + "]",
        groups,
        List.copyOf(commands));
  }

  /**
   * The first server when it is an absolute http(s) URL without template variables, else empty. The
   * parser fills in a relative {@code /} server when the document declares none, and a relative URL
   * cannot be the base of a request, so only absolute URLs become the default.
   */
  static String defaultBaseUrl(List<Server> servers) {
    if (servers.isEmpty() || !ABSOLUTE_HTTP_URL.matcher(servers.get(0).url()).matches()) {
      return "";
    }
    return servers.get(0).url();
  }

  private static List<String> apiKeyNames(List<SecurityScheme> schemes, String location) {
    return schemes.stream()
        .filter(s -> s.kind() == Kind.API_KEY && location.equals(s.in()) && s.paramName() != null)
        .map(s -> quote(s.paramName()))
        .toList();
  }
}
