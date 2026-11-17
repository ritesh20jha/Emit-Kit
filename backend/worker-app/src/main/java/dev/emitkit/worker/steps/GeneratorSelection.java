package dev.emitkit.worker.steps;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.gen.core.GeneratorId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GeneratorSelection {

  private static final Map<String, GeneratorId> SDK_LANGUAGES =
      Map.of(
          "typescript", GeneratorId.TS_SDK,
          "python", GeneratorId.PY_SDK,
          "java", GeneratorId.JAVA_SDK);

  private GeneratorSelection() {}

  static List<GeneratorId> select(List<OutputKind> outputs, List<String> sdkLanguages) {
    Set<GeneratorId> selected = EnumSet.noneOf(GeneratorId.class);
    for (OutputKind output : outputs) {
      switch (output) {
        case SDK -> {
          selected.addAll(sdkGenerators(sdkLanguages));
          selected.add(GeneratorId.WORKFLOWS);
        }
        case CLI -> selected.add(GeneratorId.CLI);
        case MCP -> selected.add(GeneratorId.MCP);
        case DOCS -> selected.add(GeneratorId.DOCS);
      }
    }
    return List.copyOf(selected);
  }

  /**
   * @throws AppException {@code GENERATOR_ERROR} for a language that is not a known SDK
   */
  private static Set<GeneratorId> sdkGenerators(List<String> languages) {
    Set<GeneratorId> result = EnumSet.noneOf(GeneratorId.class);
    for (String language : languages) {
      GeneratorId id =
          language == null ? null : SDK_LANGUAGES.get(language.toLowerCase(Locale.ROOT));
      if (id == null) {
        throw new AppException(ErrorCode.GENERATOR_ERROR, "unknown SDK language: " + language);
      }
      result.add(id);
    }
    return result;
  }
}
