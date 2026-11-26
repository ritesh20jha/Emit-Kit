package dev.emitkit.gen.cli;

import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command, file and flag names derived from spec identifiers. */
final class Names {

  private static final NameStrategy TYPESCRIPT = NameStrategy.forLanguage(Language.TYPESCRIPT);

  /** Command names that would shadow Commander's built-in help command. */
  static final Set<String> RESERVED_COMMANDS = Set.of("help");

  private Names() {}

  /** Kebab-case file-safe name, such as {@code list-pets} for {@code listPets}. */
  static String kebab(String raw) {
    return TYPESCRIPT.file(raw);
  }

  /**
   * Makes each name unique in list order. A name that is reserved, or already taken by an earlier
   * entry, gets the first free {@code -2}, {@code -3}, ... suffix. The result is index-aligned.
   */
  static List<String> unique(List<String> names, Set<String> reserved) {
    Set<String> taken = new HashSet<>(reserved);
    List<String> out = new ArrayList<>();
    for (String name : names) {
      String candidate = name;
      for (int n = 2; taken.contains(candidate); n++) {
        candidate = name + "-" + n;
      }
      taken.add(candidate);
      out.add(candidate);
    }
    return List.copyOf(out);
  }

  /**
   * The key Commander stores a {@code --flag} value under: the flag with each dash removed and the
   * following word capitalised, so {@code pet-id} becomes {@code petId}.
   */
  static String optionKey(String flag) {
    String[] words = flag.split("-", -1);
    StringBuilder key = new StringBuilder(words[0]);
    for (int i = 1; i < words.length; i++) {
      key.append(capitalise(words[i]));
    }
    return key.toString();
  }

  static String capitalise(String word) {
    return word.isEmpty()
        ? word
        : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
  }
}
