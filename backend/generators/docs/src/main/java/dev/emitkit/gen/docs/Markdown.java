package dev.emitkit.gen.docs;

import org.jspecify.annotations.Nullable;

/** Markdown text helpers shared by the docs views. */
final class Markdown {

  private Markdown() {}

  /**
   * Text for a table cell or heading: one line, with pipes escaped so the cell does not split.
   * {@code null} becomes empty.
   */
  static String cell(@Nullable String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").strip().replace("|", "\\|");
  }
}
