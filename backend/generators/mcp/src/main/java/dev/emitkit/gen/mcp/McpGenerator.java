package dev.emitkit.gen.mcp;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
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
      tools.add(
          new Tool(
              toolName(op.operationId(), taken),
              description(op),
              op.method(),
              op.path(),
              params(op),
              op.requestBody() != null,
              inputSchema(op, renderer)));
    }
    return List.copyOf(tools);
  }

  /**
   * One MCP tool.
   *
   * @param name snake_case, at most 64 characters, unique within the server
   * @param description one line: the summary, else the description, else {@code METHOD path}
   * @param method HTTP method
   * @param path path template with {@code {name}} placeholders
   * @param params the operation's parameters, in declaration order
   * @param hasBody whether the operation has a request body, passed as the {@code body} argument
   * @param inputSchema the arguments as a JSON Schema object
   */
  record Tool(
      String name,
      String description,
      String method,
      String path,
      List<Param> params,
      boolean hasBody,
      Map<String, Object> inputSchema) {

    /**
     * A parameter's name and location: {@code path}, {@code query}, {@code header} or {@code
     * cookie}.
     */
    record Param(String name, String in) {}

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
                param.put("in", p.in());
                return param;
              })
              .toList());
      out.put("hasBody", hasBody);
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

  static String description(Operation op) {
    String text = firstNonBlank(op.summary(), op.description());
    return text == null ? op.method() + " " + op.path() : oneLine(text);
  }

  /** Collapses whitespace runs, so the text is one line. */
  static String oneLine(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  private static List<Tool.Param> params(Operation op) {
    return op.parameters().stream()
        .map(p -> new Tool.Param(p.name(), p.in().name().toLowerCase(Locale.ROOT)))
        .toList();
  }

  /**
   * {@code {type: "object", properties: {<params>, body}, required: [...]}}. A parameter and the
   * body share one namespace, so a parameter named {@code body} keeps its own property.
   */
  private static Map<String, Object> inputSchema(Operation op, JsonSchemaRenderer renderer) {
    Map<String, Object> properties = new LinkedHashMap<>();
    Set<String> required = new LinkedHashSet<>();
    for (Parameter p : op.parameters()) {
      properties.putIfAbsent(p.name(), renderer.render(p.schema(), p.description()));
      if (p.required()) {
        required.add(p.name());
      }
    }
    RequestBody body = op.requestBody();
    if (body != null) {
      properties.putIfAbsent("body", renderer.render(body.schema(), null));
      if (body.required()) {
        required.add("body");
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

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second != null && !second.isBlank() ? second : null;
  }
}
