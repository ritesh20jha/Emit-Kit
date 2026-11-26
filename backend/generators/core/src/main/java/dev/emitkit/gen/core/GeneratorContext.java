package dev.emitkit.gen.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Settings shared by all generators for one run.
 *
 * <p>{@code outputDir} is the directory prefix for generated output, conventionally {@code
 * ".emitkit/"}. The default is applied where the project configuration is parsed, not here.
 *
 * @param outputDir relative directory prefix for generated output
 * @param version version stamped into generated packages
 * @param names package coordinates for each ecosystem
 * @param sdkLanguages SDK languages requested for this run
 * @param ai optional AI assistant for docs examples
 */
public record GeneratorContext(
    String outputDir,
    String version,
    PackageNames names,
    List<String> sdkLanguages,
    Optional<DocsAiAssistant> ai) {

  public GeneratorContext {
    Objects.requireNonNull(outputDir, "outputDir");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(names, "names");
    sdkLanguages = List.copyOf(Objects.requireNonNull(sdkLanguages, "sdkLanguages"));
    Objects.requireNonNull(ai, "ai");
  }
}
