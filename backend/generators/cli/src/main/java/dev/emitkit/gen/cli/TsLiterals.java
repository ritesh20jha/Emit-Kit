package dev.emitkit.gen.cli;

/** Escaping for spec-derived text rendered into TypeScript source and JSON. */
final class TsLiterals {

  private TsLiterals() {}

  /**
   * Renders {@code value} as a double-quoted string literal. Backslashes, quotes, control
   * characters and the line and paragraph separators are escaped, so no value can end the literal.
   */
  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /** Collapses whitespace runs to single spaces and trims, for one-line text. */
  static String oneLine(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }
}
