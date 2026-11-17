package dev.emitkit.worker.steps;

import static dev.emitkit.gen.core.GeneratorId.CLI;
import static dev.emitkit.gen.core.GeneratorId.DOCS;
import static dev.emitkit.gen.core.GeneratorId.JAVA_SDK;
import static dev.emitkit.gen.core.GeneratorId.MCP;
import static dev.emitkit.gen.core.GeneratorId.PY_SDK;
import static dev.emitkit.gen.core.GeneratorId.TS_SDK;
import static dev.emitkit.gen.core.GeneratorId.WORKFLOWS;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.gen.core.GeneratorId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Chooses the generators a run runs, from the outputs and SDK languages of its config. */
final class GeneratorSelection {

  /** The SDK languages as stored in {@code project_configs.sdk_languages}. */
  private static final Map<String, GeneratorId> SDK_LANGUAGES =
      Map.of("typescript", TS_SDK, "python", PY_SDK, "java", JAVA_SDK);

  private GeneratorSelection() {}

  /**
   * The generators for {@code outputs}, in {@link GeneratorId} order. SDK adds one generator per
   * language and the workflows generator.
   *
   * @throws AppException {@code GENERATOR_ERROR} if a language is not one of the known SDKs
   */
  static List<GeneratorId> select(List<OutputKind> outputs, List<String> sdkLanguages) {
    Set<GeneratorId> selected = EnumSet.noneOf(GeneratorId.class);
    for (OutputKind output : outputs) {
      switch (output) {
        case SDK -> {
          selected.addAll(sdkGenerators(sdkLanguages));
          selected.add(WORKFLOWS);
        }
        case CLI -> selected.add(CLI);
        case MCP -> selected.add(MCP);
        case DOCS -> selected.add(DOCS);
      }
    }
    return List.copyOf(selected);
  }

  private static Set<GeneratorId> sdkGenerators(List<String> languages) {
    Set<GeneratorId> result = EnumSet.noneOf(GeneratorId.class);
    for (String language : languages) {
      GeneratorId id =
          language == null ? null : SDK_LANGUAGES.get(language.toLowerCase(Locale.ROOT));
      if (id == null) {
        throw new AppException(
            ErrorCode.GENERATOR_ERROR, "unknown SDK language: " + language + ".");
      }
      result.add(id);
    }
    return result;
  }
}
