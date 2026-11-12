package dev.emitkit.gen.mcp;

import java.util.List;
import java.util.Map;

/**
 * Writes the value tree the generator builds (maps, lists, strings, numbers, booleans and null) as
 * JSON, indented two spaces per level. Map order is kept, so output is byte-stable. The result is
 * also a valid TypeScript expression.
 */
final class Json {

  private Json() {}

  /** {@code value} as pretty-printed JSON, starting at column 0. */
  static String write(Object value) {
    var out = new StringBuilder();
    write(value, 0, out);
    return out.toString();
  }

  /** {@code value} as a double-quoted JSON string, escaped so no character can end the literal. */
  static String quote(String value) {
    var out = new StringBuilder("\"");
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

  private static void write(Object value, int depth, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case String s -> out.append(quote(s));
      case Boolean b -> out.append(b);
      case Number n -> out.append(n);
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          separator(first, depth, out);
          out.append(quote(String.valueOf(entry.getKey()))).append(": ");
          write(entry.getValue(), depth + 1, out);
          first = false;
        }
        close(map.isEmpty(), '}', depth, out);
      }
      case List<?> list -> {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
          separator(first, depth, out);
          write(item, depth + 1, out);
          first = false;
        }
        close(list.isEmpty(), ']', depth, out);
      }
      default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass());
    }
  }

  private static void separator(boolean first, int depth, StringBuilder out) {
    if (!first) {
      out.append(',');
    }
    out.append('\n');
    out.append("  ".repeat(depth + 1));
  }

  private static void close(boolean empty, char bracket, int depth, StringBuilder out) {
    if (empty) {
      out.append(bracket);
      return;
    }
    out.append('\n').append("  ".repeat(depth)).append(bracket);
  }
}
