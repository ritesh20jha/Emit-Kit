package dev.emitkit.spec.parse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the test documents under {@code src/test/resources/specs}. */
final class Fixtures {

  private Fixtures() {}

  static String read(String name) {
    try (var in = Fixtures.class.getResourceAsStream("/specs/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
