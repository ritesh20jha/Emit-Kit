package dev.emitkit.gen.core.fixtures;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared OpenAPI documents for generator tests. Hand-written specs live under {@code /specs/}; the
 * generated {@code big-200-ops} document lives under {@code /golden-inputs/}.
 */
public final class FixtureSpecs {

  public static final List<String> ALL =
      List.of(
          "petstore",
          "auth-schemes",
          "polymorphism",
          "enums",
          "pagination",
          "no-operation-ids",
          "reserved-words",
          "deep-nesting",
          "multi-tag",
          "big-200-ops");

  private static final List<String> FOLDERS = List.of("/specs/", "/golden-inputs/");

  private FixtureSpecs() {}

  /** The YAML text of the named fixture, as stored on the classpath. */
  public static String raw(String name) {
    for (String folder : FOLDERS) {
      try (var in = FixtureSpecs.class.getResourceAsStream(folder + name + ".yaml")) {
        if (in != null) {
          return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    throw new IllegalArgumentException("missing fixture " + name);
  }

  /** The named fixture parsed into the model. Fails when the document does not parse cleanly. */
  public static ApiModel load(String name) {
    ParseResult result = new SpecParser().parse(raw(name));
    @Nullable ApiModel model = result.model();
    if (model == null || !result.errors().isEmpty()) {
      throw new IllegalStateException(name + " did not parse: " + result.errors());
    }
    return model;
  }
}
