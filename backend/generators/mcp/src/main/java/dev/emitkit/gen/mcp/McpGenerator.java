package dev.emitkit.gen.mcp;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Emits a stdio Model Context Protocol server under {@code {outputDir}mcp/}: one tool per
 * operation, the tool list with JSON Schema input schemas, the server entry point, package metadata
 * and a README.
 *
 * <p>Output is byte-stable: operations keep model order and no timestamps are written.
 */
public final class McpGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("mcp");

  /** Python member names are snake_case, the form MCP tool names use. */
  private static final NameStrategy NAMES = NameStrategy.forLanguage(Language.PYTHON);

  private static final int MAX_TOOL_NAME_LENGTH = 64;

  @Override
  public GeneratorId id() {
    return GeneratorId.MCP;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    McpView view = McpView.of(model, ctx, tools(model));
    String root = mcpRoot(ctx.outputDir());
    return List.of(
        generated(root + "package.json", "package.json", view),
        generated(root + "tsconfig.json", "tsconfig.json", view),
        generated(root + "README.md", "README.md", view),
        generated(root + "src/server.ts", "server.ts", view),
        generated(root + "src/tools.ts", "tools.ts", view));
  }

  /** One tool per operation, in model order, with names unique within the server. */
  static List<Tool> tools(ApiModel model) {
    var renderer = new JsonSchemaRenderer(model.schemas());
    Set<String> taken = new HashSet<>();
    List<Tool> tools = new ArrayList<>();
    for (Operation op : model.operations()) {
      List<Parameter> parameters =
          op.parameters().stream().filter(p -> !isReservedHeader(p)).toList();
      boolean hasBody = op.requestBody() != null;
      List<String> args = argumentNames(parameters, hasBody);
      tools.add(
          new Tool(
              toolName(op.operationId(), taken),
              description(op),
              op.method(),
              op.path(),
              params(parameters, args),
              hasBody,
              op.security(),
              inputSchema(parameters, args, op.requestBody(), renderer)));
    }
    return List.copyOf(tools);
  }

  /**
   * One MCP tool.
   *
   * @param name snake_case, at most 64 characters, unique within the server
   * @param description the summary, a blank line and the description; see {@link #description}
   * @param method HTTP method
   * @param path path template with {@code {name}} placeholders
   * @param params the operation's parameters, in declaration order
   * @param hasBody whether the operation has a request body, passed as the {@code body} argument
   * @param security scheme names of the operation's effective security requirement; empty when the
   *     tool sends no credentials
   * @param inputSchema the arguments as a JSON Schema object
   */
  record Tool(
      String name,
      String description,
      String method,
      String path,
      List<Param> params,
      boolean hasBody,
      List<String> security,
      Map<String, Object> inputSchema) {

    /**
     * A parameter's wire name, the argument name the tool takes its value under, and its location:
     * {@code path}, {@code query}, {@code header} or {@code cookie}. The runtime reads {@code
     * args[arg]} and sends the value under {@code name}.
     */
    record Param(String name, String arg, String in) {}

    String inputSchemaJson() {
      return Json.write(inputSchema);
    }

    /** The tool as a JSON object for {@code tools.ts}, with keys in a fixed order. */
    Map<String, Object> asMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("name", name);
      out.put("description", description);
      out.put("method", method);
      out.put("path", path);
      out.put(
          "params",
          params.stream()
              .map(p -> {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.name());
                param.put("arg", p.arg());
                param.put("in", p.in());
                return param;
              })
              .toList());
      out.put("hasBody", hasBody);
      out.put("security", security);
      out.put("inputSchema", inputSchema);
      return out;
    }
  }

  /**
   * {@code snake_case(operationId)} cut to 64 characters. A name already taken gets a {@code _2},
   * {@code _3}, ... suffix, with the base cut again so the total still fits.
   */
  static String toolName(String operationId, Set<String> taken) {
    String base = NAMES.member(operationId);
    String name = truncate(base, MAX_TOOL_NAME_LENGTH);
    for (int n = 2; !taken.add(name); n++) {
      String suffix = "_" + n;
      name = truncate(base, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
    }
    return name;
  }

  /**
   * The summary, a blank line, then the description. The description alone stands in when there is
   * no summary, and the summary alone when the description repeats it. With neither, the tool is
   * described as {@code METHOD path}.
   */
  static String description(Operation op) {
    String summary = op.summary() == null ? "" : oneLine(op.summary());
    String description = op.description() == null ? "" : op.description().strip();
    if (summary.isEmpty()) {
      return description.isEmpty() ? op.method() + " " + op.path() : description;
    }
    if (description.isEmpty() || oneLine(description).equals(summary)) {
      return summary;
    }
    return summary + "\n\n" + description;
  }

  /** Collapses whitespace runs, so the text is one line. */
  static String oneLine(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  /**
   * Header names the server sets itself: Authorization, Accept and Content-Type. A header parameter
   * with one of these names, compared without case, is not a tool input.
   */
  private static final Set<String> RUNNER_HEADERS =
      Set.of("authorization", "accept", "content-type");

  private static boolean isReservedHeader(Parameter p) {
    return p.in() == ParamLocation.HEADER
        && RUNNER_HEADERS.contains(p.name().toLowerCase(Locale.ROOT));
  }

  /** The name the request body is passed under. It is reserved in every tool that has a body. */
  private static final String BODY_ARG = "body";

  /**
   * One argument name per parameter, in order. A parameter keeps its own name while no earlier
   * argument has it. Otherwise it takes its location and camelCase name, such as {@code headerId},
   * and only then a numeric suffix, such as {@code headerId2}. {@code body} is reserved when the
   * tool has a request body, so a parameter with that name is renamed too.
   */
  static List<String> argumentNames(List<Parameter> parameters, boolean hasBody) {
    Set<String> taken = new HashSet<>();
    if (hasBody) {
      taken.add(BODY_ARG);
    }
    List<String> out = new ArrayList<>();
    for (Parameter p : parameters) {
      String arg = taken.contains(p.name()) ? firstFree(prefixedArg(p), taken) : p.name();
      taken.add(arg);
      out.add(arg);
    }
    return List.copyOf(out);
  }

  /** The location, then the name in camelCase: header {@code id} gives {@code headerId}. */
  private static String prefixedArg(Parameter p) {
    String location = p.in().name().toLowerCase(Locale.ROOT);
    return location + capitalise(camelCase(p.name()));
  }

  /** The name's words joined in camelCase: {@code X-Trace-Id} gives {@code xTraceId}. */
  private static String camelCase(String name) {
    StringBuilder out = new StringBuilder();
    for (String word : name.split("[^\\p{L}\\p{N}]+")) {
      if (word.isEmpty()) {
        continue;
      }
      out.append(out.isEmpty() ? lowercaseFirst(word) : capitalise(word));
    }
    return out.toString();
  }

  /** {@code name}, or the first {@code name2}, {@code name3}, ... that is not taken. */
  private static String firstFree(String name, Set<String> taken) {
    String candidate = name;
    for (int n = 2; taken.contains(candidate); n++) {
      candidate = name + n;
    }
    return candidate;
  }

  private static String capitalise(String word) {
    return word.isEmpty()
        ? word
        : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
  }

  private static String lowercaseFirst(String word) {
    return word.substring(0, 1).toLowerCase(Locale.ROOT) + word.substring(1);
  }

  private static List<Tool.Param> params(List<Parameter> parameters, List<String> args) {
    List<Tool.Param> out = new ArrayList<>();
    for (int i = 0; i < parameters.size(); i++) {
      Parameter p = parameters.get(i);
      out.add(new Tool.Param(p.name(), args.get(i), p.in().name().toLowerCase(Locale.ROOT)));
    }
    return List.copyOf(out);
  }

  /**
   * {@code {type: "object", properties: {<args>, body}, required: [...]}}, keyed by argument name.
   * Argument names are unique, and {@code body} is reserved, so no property is overwritten.
   */
  private static Map<String, Object> inputSchema(
      List<Parameter> parameters,
      List<String> args,
      @Nullable RequestBody body,
      JsonSchemaRenderer renderer) {
    Map<String, Object> properties = new LinkedHashMap<>();
    Set<String> required = new LinkedHashSet<>();
    for (int i = 0; i < parameters.size(); i++) {
      Parameter p = parameters.get(i);
      String arg = args.get(i);
      properties.put(arg, renderer.render(p.schema(), p.description()));
      if (p.required()) {
        required.add(arg);
      }
    }
    if (body != null) {
      properties.put(BODY_ARG, renderer.render(body.schema(), null));
      if (body.required()) {
        required.add(BODY_ARG);
      }
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", List.copyOf(required));
    return schema;
  }

  /** {@code outputDir} with trailing slashes normalised to exactly one, then {@code mcp/}. */
  static String mcpRoot(String outputDir) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "mcp/";
  }

  private static GeneratedFile generated(String path, String template, McpView view) {
    return new GeneratedFile(path, TEMPLATES.render(template, view), FileKind.GENERATED);
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}
