package dev.emitkit.spec.naming;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Maps raw spec identifiers to names that are valid in one target language. */
public interface NameStrategy {

  /** PascalCase type name, reserved-safe. */
  String type(String raw);

  /** Method or field: camel (TypeScript, Java) or snake (Python), reserved-safe. */
  String member(String raw);

  /** File stem: kebab (TypeScript), snake (Python), Pascal (Java). */
  String file(String raw);

  boolean isReserved(String name);

  static NameStrategy forLanguage(Language language) {
    Set<String> reserved = ReservedWords.of(language);
    return new NameStrategy() {
      @Override
      public String type(String raw) {
        return escape(PascalCase.join(words(raw)));
      }

      @Override
      public String member(String raw) {
        List<String> parts = words(raw);
        return escape(
            switch (language) {
              case PYTHON -> SnakeCase.join(parts, "_");
              case TYPESCRIPT, JAVA -> CamelCase.join(parts);
            });
      }

      @Override
      public String file(String raw) {
        List<String> parts = words(raw);
        return digitSafe(
            switch (language) {
              case TYPESCRIPT -> SnakeCase.join(parts, "-");
              case PYTHON -> SnakeCase.join(parts, "_");
              case JAVA -> PascalCase.join(parts);
            });
      }

      @Override
      public boolean isReserved(String name) {
        return reserved.contains(name);
      }

      private String escape(String name) {
        String safe = digitSafe(name);
        return isReserved(safe) ? safe + "_" : safe;
      }
    };
  }

  /** Splits on separators (`-`, `_`, space, `.`) and on case changes and acronym ends. */
  private static List<String> words(String raw) {
    return Arrays.stream(raw.split("[-_ .]+"))
        .flatMap(
            part -> Arrays.stream(part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")))
        .filter(word -> !word.isEmpty())
        .toList();
  }

  private static String digitSafe(String name) {
    return !name.isEmpty() && Character.isDigit(name.charAt(0)) ? "_" + name : name;
  }
}
