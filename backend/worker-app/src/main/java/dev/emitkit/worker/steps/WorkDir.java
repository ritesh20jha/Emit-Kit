package dev.emitkit.worker.steps;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.worker.messaging.WorkerProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a run's generated files wait between steps. Layout under {@code {work-dir}/{runId}/}: the
 * files at their repo-relative paths in {@code files/}, and {@code files.json}, a list of {@code
 * {path, kind}} entries. {@code files.json} is written last, so its presence means the files are
 * complete.
 */
@Component
public final class WorkDir {

  static final String FILES = "files";
  static final String MANIFEST = "files.json";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Path base;

  @Autowired
  public WorkDir(WorkerProperties props) {
    this(Path.of(Objects.requireNonNull(props.workDir(), "emitkit.worker.work-dir")));
  }

  /** Creates a work dir under {@code base}. */
  public WorkDir(Path base) {
    this.base = Objects.requireNonNull(base, "base").toAbsolutePath().normalize();
  }

  /** The directory of one run. Refuses a run id that would point outside the work dir. */
  public Path of(String runId) {
    Path dir = base.resolve(runId).normalize();
    if (!dir.startsWith(base) || dir.equals(base)) {
      throw new IllegalArgumentException("run id escapes the work dir: " + runId);
    }
    return dir;
  }

  /** Whether the run's files were written completely, that is, whether the manifest exists. */
  public boolean exists(String runId) {
    return Files.isRegularFile(of(runId).resolve(MANIFEST));
  }

  /**
   * Replaces the run's files with {@code files}, at their repo-relative paths, and writes the
   * manifest. Files left by an earlier attempt are removed first.
   *
   * @throws TransientException {@code STORAGE_ERROR} if the disk write fails
   */
  public void write(String runId, List<GeneratedFile> files) {
    Path dir = of(runId);
    Path filesDir = dir.resolve(FILES);
    try {
      FileSystemUtils.deleteRecursively(dir);
      Files.createDirectories(filesDir);
      List<Entry> manifest = new ArrayList<>();
      for (GeneratedFile file : files) {
        Path target = filesDir.resolve(file.path()).normalize();
        if (target.equals(filesDir) || !target.startsWith(filesDir)) {
          // GeneratedFile already rejects these paths; this guards the disk write as well.
          throw new AppException(
              ErrorCode.GENERATOR_ERROR, "generator produced an unsafe path: " + file.path());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        manifest.add(new Entry(file.path(), file.kind()));
      }
      Files.writeString(
          dir.resolve(MANIFEST), JSON.writeValueAsString(manifest), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "generated files could not be written", Map.of(), e);
    }
  }

  /** One line of {@code files.json}. */
  public record Entry(String path, FileKind kind) {}
}
