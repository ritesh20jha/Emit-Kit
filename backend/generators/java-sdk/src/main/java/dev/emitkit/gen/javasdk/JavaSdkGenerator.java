package dev.emitkit.gen.javasdk;

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
 * Emits a Java SDK under {@code {outputDir}sdk/java/}: a Gradle build, the client with auth and
 * retries, the exception hierarchy, and a user-owned custom stub.
 *
 * <p>Output is byte-stable: collections keep model order and no timestamps are written.
 */
public final class JavaSdkGenerator implements Generator {

  private static final Templates TEMPLATES = Templates.forGenerator("java-sdk");

  /** The exception subclasses the client raises, one per status family. */
  private static final List<ExceptionClass> EXCEPTIONS =
      List.of(
          new ExceptionClass("UnauthorizedException", "401 Unauthorized, or 403 Forbidden."),
          new ExceptionClass("NotFoundException", "404 Not Found."),
          new ExceptionClass("ConflictException", "409 Conflict."),
          new ExceptionClass(
              "RateLimitException",
              "429 Too Many Requests. Raised only after the client's retries are exhausted."),
          new ExceptionClass(
              "ServerException",
              "5xx Server Error. Raised only after the client's retries are exhausted."));

  /** Name and javadoc summary of one exception subclass. */
  record ExceptionClass(String name, String summary) {}

  /** Context for {@code exception-subclass.java}: the subclass plus the package it lives in. */
  record ExceptionView(String packageName, String name, String summary) {}

  @Override
  public GeneratorId id() {
    return GeneratorId.JAVA_SDK;
  }

  @Override
  public List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx) {
    JavaClientView view = JavaClientView.of(model, ctx);
    String root = sdkRoot(ctx.outputDir());
    String src = root + "src/main/java/" + view.packagePath() + "/";
    List<GeneratedFile> files = new ArrayList<>();
    files.add(generated(root + "build.gradle.kts", "build.gradle.kts", view));
    files.add(generated(root + "settings.gradle.kts", "settings.gradle.kts", view));
    files.add(generated(root + "README.md", "README.md", view));
    files.add(generated(src + view.className() + ".java", "Client.java", view));
    files.add(generated(src + "ClientConfig.java", "ClientConfig.java", view));
    files.add(generated(src + "ApiException.java", "ApiException.java", view));
    for (ExceptionClass exception : EXCEPTIONS) {
      ExceptionView context =
          new ExceptionView(view.packageName(), exception.name(), exception.summary());
      files.add(
          new GeneratedFile(
              src + exception.name() + ".java",
              TEMPLATES.render("exception-subclass.java", context),
              FileKind.GENERATED));
    }
    files.add(
        new GeneratedFile(
            src + "custom/package-info.java",
            TEMPLATES.render("custom-package-info.java", view),
            FileKind.CUSTOM_STUB));
    files.addAll(resourceFiles());
    return List.copyOf(files);
  }

  /** Hook for per-resource and per-type files. Empty until the resource generator lands. */
  private static List<GeneratedFile> resourceFiles() {
    return List.of();
  }

  private static GeneratedFile generated(String path, String template, JavaClientView view) {
    return new GeneratedFile(path, TEMPLATES.render(template, view), FileKind.GENERATED);
  }

  /**
   * {@code outputDir} with any trailing slashes normalised to exactly one, then {@code sdk/java/}.
   */
  static String sdkRoot(String outputDir) {
    String base = outputDir.replaceAll("/+$", "");
    return (base.isEmpty() ? "" : base + "/") + "sdk/java/";
  }
}
