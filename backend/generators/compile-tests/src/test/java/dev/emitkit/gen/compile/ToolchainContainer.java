package dev.emitkit.gen.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import dev.emitkit.gen.core.GeneratedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the toolchain image's {@code check.sh} over generated files, the way {@code
 * deploy/docker/toolchain/test.sh} does: the input tar, {@code --network none --read-only --user
 * 10001}, and {@code report.json} read back from the bind-mounted work directory.
 */
final class ToolchainContainer {

  static final String IMAGE_PROPERTY = "emitkit.toolchain.image";
  static final String RUNTIME_PROPERTY = "emitkit.sandbox.runtime";
  private static final String DEFAULT_IMAGE = "emitkit/toolchain:test";
  private static final String DEFAULT_RUNTIME = "runc";
  private static final Duration TIMEOUT = Duration.ofSeconds(180);

  private final String image;
  private final String runtime;

  ToolchainContainer() {
    this(
        System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE),
        System.getProperty(RUNTIME_PROPERTY, DEFAULT_RUNTIME));
  }

  private ToolchainContainer(String image, String runtime) {
    if (!runtime.equals("runc") && !runtime.equals("runsc")) {
      throw new IllegalArgumentException(RUNTIME_PROPERTY + " must be runc or runsc: " + runtime);
    }
    this.image = image;
    this.runtime = runtime;
  }

  /** Why the image cannot be used here, or {@code null} when it can. */
  String unavailableReason() {
    try {
      if (exec(List.of("docker", "image", "inspect", image), null, TIMEOUT) == 0) {
        return null;
      }
      return "toolchain image "
          + image
          + " is not available; build it with: docker build -t "
          + image
          + " deploy/docker/toolchain";
    } catch (IOException e) {
      return "docker is not available: " + e.getMessage();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted while probing docker";
    }
  }

  /** Runs check.sh over {@code files} (repo-relative paths) for the given languages. */
  Report check(List<GeneratedFile> files, Set<String> languages) {
    try {
      return checkIn(files, languages);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running the toolchain", e);
    }
  }

  private Report checkIn(List<GeneratedFile> files, Set<String> languages)
      throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory("compile-tests-");
    try {
      // The container runs as uid 10001, which must be able to write here.
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
      writeInput(dir.resolve("in.tar"), files, languages);
      Path log = dir.resolve("docker.log");
      String name = "emitkit-compile-" + UUID.randomUUID();
      int code =
          exec(
              List.of(
                  "docker",
                  "run",
                  "--rm",
                  "--name",
                  name,
                  "--runtime=" + runtime,
                  "--network",
                  "none",
                  "--read-only",
                  "--user",
                  "10001",
                  "-v",
                  dir + ":/work",
                  image),
              log,
              TIMEOUT);
      if (code == -1) {
        exec(List.of("docker", "rm", "-f", name), null, TIMEOUT);
        throw new IllegalStateException(
            "toolchain run timed out after " + TIMEOUT + ":\n" + read(log));
      }
      Path report = dir.resolve("report.json");
      if (!Files.isRegularFile(report)) {
        throw new IllegalStateException(
            "toolchain wrote no report.json (docker exit " + code + "):\n" + read(log));
      }
      return JsonMapper.builder().build().readValue(report.toFile(), Report.class);
    } finally {
      cleanup(dir);
    }
  }

  /** Writes the check.sh input: {@code src/<path>} per file plus {@code languages.txt}. */
  private static void writeInput(Path tar, List<GeneratedFile> files, Set<String> languages)
      throws IOException {
    try (var out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
      out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (GeneratedFile file : files) {
        putEntry(out, "src/" + file.path(), file.content().getBytes(UTF_8));
      }
      putEntry(out, "languages.txt", (String.join("\n", languages) + "\n").getBytes(UTF_8));
      out.finish();
    }
  }

  private static void putEntry(TarArchiveOutputStream out, String name, byte[] bytes)
      throws IOException {
    var entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    out.putArchiveEntry(entry);
    out.write(bytes);
    out.closeArchiveEntry();
  }

  /**
   * Files the container wrote belong to uid 10001, which the host user cannot delete. Clear them as
   * root inside the image first (as test.sh does), then remove the directory on the host.
   */
  private void cleanup(Path dir) throws IOException, InterruptedException {
    exec(
        List.of(
            "docker",
            "run",
            "--rm",
            "--user",
            "0",
            "--network",
            "none",
            "-v",
            dir + ":/w",
            "--entrypoint",
            "sh",
            image,
            "-c",
            "rm -rf /w/* /w/.[!.]*"),
        null,
        TIMEOUT);
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  /** Runs a command with stdout and stderr sent to {@code log} (or discarded); -1 on timeout. */
  private static int exec(List<String> command, Path log, Duration timeout)
      throws IOException, InterruptedException {
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.redirectOutput(
        log == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(log.toFile()));
    Process process = builder.start();
    if (!process.waitFor(timeout.toMillis(), MILLISECONDS)) {
      process.destroyForcibly();
      return -1;
    }
    return process.exitValue();
  }

  private static String read(Path log) throws IOException {
    return log != null && Files.exists(log) ? Files.readString(log, UTF_8) : "";
  }

  /** The parsed report.json written by check.sh. */
  record Report(List<Result> results) {}

  /** One tool run in one directory. */
  record Result(String tool, String dir, int exitCode, String output) {}
}
