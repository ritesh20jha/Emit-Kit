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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * A run's files under {@code {work-dir}/{runId}/}: {@code files/}, {@code files.json} and {@code
 * generation}, which is written last so that its presence marks a complete write.
 */
@Component
public final class WorkDir {

  static final String FILES = "files";
  static final String MANIFEST = "files.json";
  static final String GENERATION = "generation";

  private static final Logger LOG = LoggerFactory.getLogger(WorkDir.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Path base;

  @Autowired
  public WorkDir(WorkerProperties props) {
    this(Path.of(Objects.requireNonNull(props.workDir(), "emitkit.worker.work-dir")));
  }

  public WorkDir(Path base) {
    this.base = Objects.requireNonNull(base, "base").toAbsolutePath().normalize();
  }

  public Path root() {
    return base;
  }

  /** The directory of one run. Refuses a run id that would point outside the root. */
  public Path of(String runId) {
    Path dir = base.resolve(runId).normalize();
    if (!dir.startsWith(base) || dir.equals(base)) {
      throw new IllegalArgumentException("run id escapes the work dir: " + runId);
    }
    return dir;
  }

  public Optional<String> generation(String runId) {
    Path marker = of(runId).resolve(GENERATION);
    try {
      return Files.isRegularFile(marker)
          ? Optional.of(Files.readString(marker, StandardCharsets.UTF_8))
          : Optional.empty();
    } catch (IOException e) {
      throw new TransientException(ErrorCode.STORAGE_ERROR, "generation unreadable", Map.of(), e);
    }
  }

  /** Replaces the run's files and records {@code generationId}. */
  public void write(String runId, String generationId, List<GeneratedFile> files) {
    Path dir = of(runId);
    Path filesDir = dir.resolve(FILES);
    try {
      FileSystemUtils.deleteRecursively(dir);
      Files.createDirectories(filesDir);
      List<Entry> manifest = new ArrayList<>();
      for (GeneratedFile file : files) {
        Path target = filesDir.resolve(file.path()).normalize();
        if (target.equals(filesDir) || !target.startsWith(filesDir)) {
          throw new AppException(
              ErrorCode.GENERATOR_ERROR, "generator produced an unsafe path: " + file.path());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        manifest.add(new Entry(file.path(), file.kind()));
      }
      Files.writeString(
          dir.resolve(MANIFEST), JSON.writeValueAsString(manifest), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(GENERATION), generationId, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "generated files could not be written", Map.of(), e);
    }
  }

  public void delete(String runId) {
    try {
      FileSystemUtils.deleteRecursively(of(runId));
    } catch (IOException e) {
      LOG.warn("could not delete the work dir of run {}", runId, e);
    }
  }

  public record Entry(String path, FileKind kind) {}
}
