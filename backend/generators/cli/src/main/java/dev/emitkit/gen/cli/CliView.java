package dev.emitkit.gen.cli;

import static dev.emitkit.gen.cli.TsLiterals.oneLine;
import static dev.emitkit.gen.cli.TsLiterals.quote;

import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

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
    String securitySchemesLiteral,
    List<CommandGroup> groups,
    List<CommandLine> commands) {

  private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile("https?://[^{}\\s]+");

  private static final String BEARER = "{ kind: \"bearer\" }";

  /** One README entry: the full command line a user types, such as {@code petstore pets}. */
  record CommandLine(String text) {}

  static CliView of(ApiModel model, GeneratorContext ctx) {
    PackageNames names = ctx.names();
    String scope = names.npmScope();
    String packageName =
        (scope == null || scope.isEmpty() ? "" : "@" + scope + "/") + names.npmName() + "-cli";
    String defaultUrl = defaultBaseUrl(model.servers());
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
        securitySchemesLiteral(model.securitySchemes().values()),
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

  /**
   * A TypeScript array literal of {@code [name, credential]} pairs, one per scheme that a command
   * can send a credential for. Schemes the CLI cannot fill, such as HTTP basic, are left out.
   */
  static String securitySchemesLiteral(Collection<SecurityScheme> schemes) {
    List<String> entries = new ArrayList<>();
    for (SecurityScheme scheme : schemes) {
      String credential = credential(scheme);
      if (credential != null) {
        entries.add("[" + quote(scheme.name()) + ", " + credential + "]");
      }
    }
    return entries.isEmpty() ? "[]" : "[\n  " + String.join(",\n  ", entries) + ",\n]";
  }

  /** The TypeScript {@code Credential} value for a scheme, or null when no option fills it. */
  private static @Nullable String credential(SecurityScheme scheme) {
    return switch (scheme.kind()) {
      case OAUTH2, OPENID_CONNECT -> BEARER;
      case HTTP -> "bearer".equalsIgnoreCase(scheme.scheme()) ? BEARER : null;
      case API_KEY -> apiKeyCredential(scheme);
    };
  }

  private static @Nullable String apiKeyCredential(SecurityScheme scheme) {
    String in = scheme.in();
    boolean supported = "header".equals(in) || "query".equals(in) || "cookie".equals(in);
    if (!supported || scheme.paramName() == null) {
      return null;
    }
    return "{ kind: \"apiKey\", in: " + quote(in) + ", name: " + quote(scheme.paramName()) + " }";
  }
}
