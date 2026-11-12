package dev.emitkit.gen.cli;

import static dev.emitkit.gen.cli.TsLiterals.oneLine;
import static dev.emitkit.gen.cli.TsLiterals.quote;

import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One subcommand and the request it sends. Spec text reaches the TypeScript only as literals. */
record CommandOperation(
    String name,
    String nameLiteral,
    String descriptionLiteral,
    String methodLiteral,
    String pathExpr,
    List<Flag> flags,
    List<Entry> query,
    List<Entry> headers,
    List<Entry> cookies,
    boolean hasQuery,
    boolean hasHeaders,
    boolean hasCookies,
    boolean hasBody,
    String contentTypeLiteral) {

  /** Global options and the body flag. A parameter with one of these flags gets a suffix. */
  private static final Set<String> RESERVED_FLAGS =
      Set.of("json", "base-url", "token", "api-key", "output", "help");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

  /**
   * A flag. {@code flagLiteral} is the quoted Commander flag, such as {@code "--pet-id <value>"}.
   */
  record Flag(boolean required, String flagLiteral, String descriptionLiteral) {}

  /** A request parameter: its wire name and the expression that yields its value. */
  record Entry(String keyLiteral, String valueExpr) {}

  static List<CommandOperation> of(List<Operation> operations) {
    List<String> names =
        Names.unique(
            operations.stream().map(op -> Names.kebab(op.operationId())).toList(),
            Names.RESERVED_COMMANDS);
    List<CommandOperation> out = new ArrayList<>();
    for (int i = 0; i < operations.size(); i++) {
      out.add(build(names.get(i), operations.get(i)));
    }
    return List.copyOf(out);
  }

  private static CommandOperation build(String name, Operation op) {
    List<Parameter> params = op.parameters();
    List<String> flags =
        Names.unique(params.stream().map(p -> Names.kebab(p.name())).toList(), RESERVED_FLAGS);
    List<Flag> flagList = new ArrayList<>();
    List<Entry> query = new ArrayList<>();
    List<Entry> headers = new ArrayList<>();
    List<Entry> cookies = new ArrayList<>();
    Map<String, String> pathKeys = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      Parameter p = params.get(i);
      String flag = flags.get(i);
      String key = Names.optionKey(flag);
      boolean required = p.required() || p.in() == ParamLocation.PATH;
      flagList.add(
          new Flag(
              required,
              quote("--" + flag + " <value>"),
              quote(firstNonBlank(p.description(), describe(p)))));
      Entry entry = new Entry(quote(p.name()), "opts[" + quote(key) + "]");
      switch (p.in()) {
        case PATH -> pathKeys.put(p.name(), key);
        case QUERY -> query.add(entry);
        case HEADER -> headers.add(entry);
        case COOKIE -> cookies.add(entry);
      }
    }
    RequestBody body = op.requestBody();
    if (body != null) {
      flagList.add(
          new Flag(
              body.required(),
              quote("--json <body|@file>"),
              quote("Request body as JSON text, or @path to read it from a file")));
    }
    return new CommandOperation(
        name,
        quote(name),
        quote(
            firstNonBlank(
                op.summary(),
                op.description(),
                op.method().toUpperCase(Locale.ROOT) + " " + op.path())),
        quote(op.method().toUpperCase(Locale.ROOT)),
        pathExpr(op.path(), pathKeys),
        List.copyOf(flagList),
        List.copyOf(query),
        List.copyOf(headers),
        List.copyOf(cookies),
        !query.isEmpty(),
        !headers.isEmpty(),
        !cookies.isEmpty(),
        body != null,
        body == null ? quote("") : quote(body.contentType()));
  }

  /**
   * The path as a TypeScript expression. Each declared path parameter becomes a {@code
   * encodeURIComponent} call on its option value; literal text is quoted. A placeholder the
   * operation does not declare stays as literal text.
   */
  static String pathExpr(String path, Map<String, String> pathKeys) {
    List<String> parts = new ArrayList<>();
    Matcher matcher = PLACEHOLDER.matcher(path);
    int last = 0;
    while (matcher.find()) {
      String key = pathKeys.get(matcher.group(1));
      if (key == null) {
        continue;
      }
      addLiteral(parts, path.substring(last, matcher.start()));
      parts.add("encodeURIComponent(String(opts[" + quote(key) + "]))");
      last = matcher.end();
    }
    addLiteral(parts, path.substring(last));
    return parts.isEmpty() ? quote("") : String.join(" + ", parts);
  }

  private static void addLiteral(List<String> parts, String text) {
    if (!text.isEmpty()) {
      parts.add(quote(text));
    }
  }

  private static String describe(Parameter p) {
    return Names.capitalise(p.in().name().toLowerCase(Locale.ROOT)) + " parameter " + p.name();
  }

  /** The first value that is non-blank after whitespace collapse, or {@code ""}. */
  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !oneLine(value).isEmpty()) {
        return oneLine(value);
      }
    }
    return "";
  }
}
