package dev.emitkit.gen.workflows;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits one GitHub Actions publish workflow per requested SDK language. Workflows live at the
 * repository root, so their paths ignore {@code outputDir}; only the working directory inside each
 * job follows it.
 *
 * <p>Output order is fixed (TypeScript, Python, Java) and unknown languages are ignored.
 */
public final class WorkflowsGenerator implements Generator {

  private static final String WORKFLOW_DIR = ".github/workflows/";
  private static final Templates TEMPLATES = Templates.forGenerator("workflows");

  private static final List<Target> TARGETS =
      List.of(
          new Target("typescript", "TypeScript", "npm", "publish-npm.yml"),
          new Target("python", "Python", "pypi", "publish-pypi.yml"),
          new Target("java", "Java", "maven", "publish-maven.yml"));

  @Override
  public GeneratorId id() {
    return GeneratorId.WORKFLOWS;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    Set<String> requested = Set.copyOf(ctx.sdkLanguages());
    List<GeneratedFile> files = new ArrayList<>();
    for (Target target : TARGETS) {
      if (!requested.contains(target.language())) {
        continue;
      }
      Map<String, Object> context =
          Map.of(
              "name",
              yamlString("Publish " + model.info().title() + " " + target.displayName() + " SDK"),
              "workDir",
              yamlString(workDir(ctx.outputDir(), target.language())));
      files.add(
          new GeneratedFile(
              WORKFLOW_DIR + "emitkit-publish-" + target.ecosystem() + ".yml",
              TEMPLATES.render(target.template(), context),
              FileKind.GENERATED));
    }
    return List.copyOf(files);
  }

  /**
   * {@code outputDir} with any trailing slashes normalised to exactly one, then {@code sdk/lang}.
   */
  static String workDir(String outputDir, String language) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "sdk/" + language;
  }

  /**
   * Renders {@code value} as a double-quoted YAML scalar. Backslashes, quotes and control or
   * line-break characters are escaped so no value can change the structure of the workflow.
   */
  static String yamlString(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (needsUnicodeEscape(c)) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  private static boolean needsUnicodeEscape(char c) {
    return c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == 0x2028 || c == 0x2029 || c == 0xFEFF;
  }

  /**
   * One supported language: its SDK language key, the name shown in the workflow name, the package
   * ecosystem used in the file name, and the template that renders it.
   */
  private record Target(String language, String displayName, String ecosystem, String template) {}
}
