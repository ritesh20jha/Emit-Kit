package dev.emitkit.gen.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratedFileTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a.txt",
        "sdk/typescript/src/index.ts",
        ".emitkit/x.md",
        "dir/.hidden/..name",
        "a...b/c"
      })
  void acceptsRelativeSlashSeparatedPaths(String path) {
    assertThatCode(() -> new GeneratedFile(path, "", FileKind.GENERATED))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "   ", "/abs", "../x", "a/../b", "a//b", "./a", "a/./b", "a\\b", "a/", "a/.."})
  void rejectsPathsOutsideTheInvariant(String path) {
    assertThatThrownBy(() -> new GeneratedFile(path, "", FileKind.GENERATED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(path);
  }
}
