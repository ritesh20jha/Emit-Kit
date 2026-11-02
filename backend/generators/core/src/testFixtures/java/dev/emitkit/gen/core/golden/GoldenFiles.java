package dev.emitkit.gen.core.golden;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import dev.emitkit.gen.core.GeneratedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Compares generator output with checked-in golden files.
 *
 * <p>Run with {@code -Dgolden.update=true} to delete the golden directory and rewrite it from the
 * generated output instead of asserting.
 */
public final class GoldenFiles {

  private static final String UPDATE_PROPERTY = "golden.update";

  private GoldenFiles() {}

  /** Compares against {@code src/test/resources/golden/{fixtureName}} of the module under test. */
  public static void assertMatches(String fixtureName, List<GeneratedFile> files) {
    assertMatches(Path.of("src", "test", "resources", "golden", fixtureName), files);
  }

  /** Compares the generated files against the golden files under {@code goldenDir}. */
  public static void assertMatches(Path goldenDir, List<GeneratedFile> files) {
    Map<String, String> generated = new TreeMap<>();
    for (GeneratedFile file : files) {
      if (generated.putIfAbsent(file.path(), file.content()) != null) {
        throw new IllegalArgumentException("duplicate generated path: " + file.path());
      }
    }
    if ("true".equals(System.getProperty(UPDATE_PROPERTY))) {
      update(goldenDir, generated);
    } else {
      compare(goldenDir, generated);
    }
  }

  private static void compare(Path goldenDir, Map<String, String> generated) {
    Map<String, String> golden = readGolden(goldenDir);

    Set<String> missing = new TreeSet<>(golden.keySet());
    missing.removeAll(generated.keySet());
    Set<String> extra = new TreeSet<>(generated.keySet());
    extra.removeAll(golden.keySet());

    String firstDiff = null;
    for (Map.Entry<String, String> entry : golden.entrySet()) {
      String actual = generated.get(entry.getKey());
      if (actual != null && !actual.equals(entry.getValue())) {
        firstDiff = unifiedDiff(entry.getKey(), entry.getValue(), actual);
        break;
      }
    }

    if (missing.isEmpty() && extra.isEmpty() && firstDiff == null) {
      return;
    }
    StringBuilder message = new StringBuilder("Golden files differ from generated output in ");
    message.append(goldenDir).append('\n');
    if (!missing.isEmpty()) {
      message.append("Missing (in golden, not generated): ").append(missing).append('\n');
    }
    if (!extra.isEmpty()) {
      message.append("Extra (generated, not in golden): ").append(extra).append('\n');
    }
    if (firstDiff != null) {
      message.append("First differing file (-golden +generated):\n").append(firstDiff).append('\n');
    }
    message
        .append("Re-run with -D")
        .append(UPDATE_PROPERTY)
        .append("=true to update the golden files.");
    throw new AssertionError(message.toString());
  }

  private static void update(Path goldenDir, Map<String, String> generated) {
    deleteRecursively(goldenDir);
    for (Map.Entry<String, String> entry : generated.entrySet()) {
      Path target = goldenDir.resolve(entry.getKey());
      try {
        Files.createDirectories(target.getParent());
        Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot write golden file " + target, e);
      }
    }
  }

  private static Map<String, String> readGolden(Path goldenDir) {
    Map<String, String> result = new TreeMap<>();
    if (!Files.isDirectory(goldenDir)) {
      return result;
    }
    List<Path> regularFiles;
    try (Stream<Path> walk = Files.walk(goldenDir)) {
      regularFiles = walk.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list golden dir " + goldenDir, e);
    }
    for (Path file : regularFiles) {
      String relPath = goldenDir.relativize(file).toString().replace('\\', '/');
      try {
        result.put(relPath, Files.readString(file, StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read golden file " + file, e);
      }
    }
    return result;
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot clear golden dir " + dir, e);
    }
  }

  private static String unifiedDiff(String path, String expected, String actual) {
    List<String> expectedLines = Arrays.asList(expected.split("\n", -1));
    List<String> actualLines = Arrays.asList(actual.split("\n", -1));
    Patch<String> patch = DiffUtils.diff(expectedLines, actualLines);
    List<String> lines =
        UnifiedDiffUtils.generateUnifiedDiff(
            "golden/" + path, "generated/" + path, expectedLines, patch, 3);
    return String.join("\n", lines);
  }
}
