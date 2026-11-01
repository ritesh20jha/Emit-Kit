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
        return switch (language) {
          case TYPESCRIPT -> digitSafe(SnakeCase.join(words(raw), "-"));
          case PYTHON -> escape(SnakeCase.join(words(raw), "_"));
          case JAVA -> type(raw);
        };
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

  /**
   * Splits on any character that is not a letter or digit, and on case changes. An acronym ends
   * before a capital that starts a word of 3+ letters, so {@code HTTPServer} splits but {@code
   * URLs} does not. Falls back to {@code value} when nothing is left.
   */
  private static List<String> words(String raw) {
    List<String> words =
        Arrays.stream(raw.split("[^\\p{L}\\p{N}]+"))
            .flatMap(part ->
                Arrays.stream(part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z]{2,})")))
            .filter(word -> !word.isEmpty())
            .toList();
    return words.isEmpty() ? List.of("value") : words;
  }

  private static String digitSafe(String name) {
    return !name.isEmpty() && Character.isDigit(name.charAt(0)) ? "_" + name : name;
  }
}
