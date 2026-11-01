package dev.emitkit.spec.naming;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.Locale;

/** camelCase joining: first word lower-case, the rest capitalised. */
final class CamelCase {

  private CamelCase() {}

  static String join(List<String> words) {
    String head = words.isEmpty() ? "" : words.get(0).toLowerCase(Locale.ROOT);
    return head + words.stream().skip(1).map(PascalCase::capitalise).collect(joining());
  }
}
