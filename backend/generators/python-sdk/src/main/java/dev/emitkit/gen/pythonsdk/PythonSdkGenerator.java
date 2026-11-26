package dev.emitkit.gen.pythonsdk;

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
 * Emits a Python SDK package under {@code {outputDir}sdk/python/}: the sync and async clients with
 * auth and retries, the error hierarchy, Pydantic models, one resource module per tag, package
 * metadata, and a user-owned custom stub.
 *
 * <p>The importable package directory is the PyPI name with {@code -} replaced by {@code _}. Output
 * is byte-stable: collections keep model order and no timestamps are written.
 */
public final class PythonSdkGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("python-sdk");

  @Override
  public GeneratorId id() {
    return GeneratorId.PY_SDK;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    PyClientView view = PyClientView.of(model, ctx);
    String root = sdkRoot(ctx.outputDir());
    String pkg = root + view.packageDir() + "/";
    PyTypeRenderer types = new PyTypeRenderer(model);
    List<GeneratedFile> files = new ArrayList<>();
    files.add(generated(root + "pyproject.toml", "pyproject.toml", view));
    files.add(generated(root + "README.md", "README.md", view));
    files.add(generated(pkg + "__init__.py", "__init__.py", view));
    files.add(generated(pkg + "client.py", "client.py", view));
    files.add(generated(pkg + "errors.py", "errors.py", view));
    files.add(generated(pkg + "models.py", "models.py", types.modelsModule()));
    files.add(
        new GeneratedFile(
            pkg + "custom/__init__.py",
            TEMPLATES.render("custom__init__.py", view),
            FileKind.CUSTOM_STUB));
    files.addAll(PyResources.files(model, types, view, pkg, TEMPLATES));
    return List.copyOf(files);
  }

  private static GeneratedFile generated(String path, String template, Object context) {
    return new GeneratedFile(path, TEMPLATES.render(template, context), FileKind.GENERATED);
  }

  /**
   * {@code outputDir} with any trailing slashes normalised to exactly one, then {@code
   * sdk/python/}.
   */
  static String sdkRoot(String outputDir) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "sdk/python/";
  }
}
