package dev.emitkit.gen.cli;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.Templates;
import dev.emitkit.spec.model.ApiModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits a Commander-based TypeScript command-line client under {@code {outputDir}cli/}: one command
 * group per tag, one subcommand per operation, and the package metadata to build it.
 *
 * <p>The CLI calls the API with {@code fetch} directly and depends on no generated SDK. Output is
 * byte-stable: collections keep model order and no timestamps are written.
 */
public final class CliGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("cli");

  @Override
  public GeneratorId id() {
    return GeneratorId.CLI;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    CliView view = CliView.of(model, ctx);
    String root = cliRoot(ctx.outputDir());
    List<GeneratedFile> files = new ArrayList<>();
    files.add(generated(root + "package.json", "package.json", view));
    files.add(generated(root + "tsconfig.json", "tsconfig.json", view));
    files.add(generated(root + "README.md", "README.md", view));
    files.add(generated(root + "src/index.ts", "index.ts", view));
    for (CommandGroup group : view.groups()) {
      files.add(
          new GeneratedFile(
              root + "src/commands/" + group.name() + ".ts",
              TEMPLATES.render("command.ts", group),
              FileKind.GENERATED));
    }
    return List.copyOf(files);
  }

  private static GeneratedFile generated(String path, String template, CliView view) {
    return new GeneratedFile(path, TEMPLATES.render(template, view), FileKind.GENERATED);
  }

  /** {@code outputDir} with any trailing slashes normalised to exactly one, then {@code cli/}. */
  static String cliRoot(String outputDir) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "cli/";
  }
}
