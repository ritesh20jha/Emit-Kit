package dev.emitkit.gen.typescriptsdk;

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
 * Emits a TypeScript SDK package under {@code {outputDir}sdk/typescript/}: the client with auth and
 * retries, the error hierarchy, package metadata, and a user-owned custom stub.
 *
 * <p>Output is byte-stable: collections keep model order and no timestamps are written.
 */
public final class TypeScriptSdkGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("typescript-sdk");

  @Override
  public GeneratorId id() {
    return GeneratorId.TS_SDK;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    TsClientView view = TsClientView.of(model, ctx);
    String root = sdkRoot(ctx.outputDir());
    List<GeneratedFile> files = new ArrayList<>();
    files.add(generated(root + "package.json", "package.json", view));
    files.add(generated(root + "tsconfig.json", "tsconfig.json", view));
    files.add(generated(root + "README.md", "README.md", view));
    files.add(generated(root + "src/client.ts", "client.ts", view));
    files.add(generated(root + "src/errors.ts", "errors.ts", view));
    files.add(generated(root + "src/index.ts", "index.ts", view));
    files.add(
        new GeneratedFile(
            root + "src/custom/index.ts",
            TEMPLATES.render("custom-index.ts", view),
            FileKind.CUSTOM_STUB));
    files.addAll(resourceFiles());
    return List.copyOf(files);
  }

  /** Hook for per-resource and per-type files. Empty until the resource generator lands. */
  private static List<GeneratedFile> resourceFiles() {
    return List.of();
  }

  private static GeneratedFile generated(String path, String template, TsClientView view) {
    return new GeneratedFile(path, TEMPLATES.render(template, view), FileKind.GENERATED);
  }

  /**
   * {@code outputDir} with any trailing slashes normalised to exactly one, then {@code
   * sdk/typescript/}.
   */
  static String sdkRoot(String outputDir) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "sdk/typescript/";
  }
}
