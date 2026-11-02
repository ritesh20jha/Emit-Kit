package dev.emitkit.gen.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.gen.core.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenFilesTest {

  @Test
  void matchingOutputPasses(@TempDir Path golden) throws IOException {
    write(golden, "a.txt", "x");
    GoldenFiles.assertMatches(golden, List.of(file("a.txt", "x")));
  }

  @Test
  void matchingNestedFilesPass(@TempDir Path golden) throws IOException {
    write(golden, "sub/deeper/b.txt", "b\n");
    GoldenFiles.assertMatches(golden, List.of(file("sub/deeper/b.txt", "b\n")));
  }

  @Test
  void differentContentFailsWithDiff(@TempDir Path golden) throws IOException {
    write(golden, "a.txt", "x");
    assertThatThrownBy(() -> GoldenFiles.assertMatches(golden, List.of(file("a.txt", "y"))))
        .hasMessageContaining("-x")
        .hasMessageContaining("+y");
  }

  @Test
  void extraAndMissingFilesReported(@TempDir Path golden) throws IOException {
    write(golden, "a.txt", "x");
    write(golden, "gone.txt", "g");
    assertThatThrownBy(() ->
            GoldenFiles.assertMatches(golden, List.of(file("a.txt", "x"), file("new.txt", "n"))))
        .hasMessageContaining("gone.txt")
        .hasMessageContaining("new.txt");
  }

  @Test
  void updateModeRewrites(@TempDir Path golden) throws IOException {
    write(golden, "a.txt", "old");
    write(golden, "stale.txt", "stale");
    String previous = System.getProperty("golden.update");
    System.setProperty("golden.update", "true");
    try {
      GoldenFiles.assertMatches(golden, List.of(file("a.txt", "new"), file("sub/b.txt", "b")));
    } finally {
      if (previous == null) {
        System.clearProperty("golden.update");
      } else {
        System.setProperty("golden.update", previous);
      }
    }
    assertThat(golden.resolve("a.txt")).hasContent("new");
    assertThat(golden.resolve("sub/b.txt")).hasContent("b");
    assertThat(golden.resolve("stale.txt")).doesNotExist();
  }

  private static void write(Path root, String relPath, String content) throws IOException {
    Path target = root.resolve(relPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  private static GeneratedFile file(String path, String content) {
    return new GeneratedFile(path, content, FileKind.GENERATED);
  }
}
