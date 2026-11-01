package dev.emitkit.spec.naming;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Locale;

/** PascalCase joining: every word capitalised, acronyms lower-cased after the first letter. */
final class PascalCase {

  private PascalCase() {}

  static String join(List<String> words) {
    return words.stream().map(PascalCase::capitalise).collect(joining());
  }

  static String capitalise(String word) {
    String lower = word.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}
