package dev.emitkit.spec.naming;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Locale;

/** Lower-case words joined by a separator: snake_case with "_", kebab-case with "-". */
final class SnakeCase {

  private SnakeCase() {}

  static String join(List<String> words, String separator) {
    return words.stream().map(word -> word.toLowerCase(Locale.ROOT)).collect(joining(separator));
  }
}
