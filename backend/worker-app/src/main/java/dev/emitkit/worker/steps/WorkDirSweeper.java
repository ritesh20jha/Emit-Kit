package dev.emitkit.worker.steps;

import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.worker.messaging.WorkerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes old work dirs of finished or unknown runs. */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class WorkDirSweeper {

  /** A ULID: only directories with this name are deleted. */
  private static final Pattern RUN_ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private static final Logger LOG = LoggerFactory.getLogger(WorkDirSweeper.class);

  private final WorkDir workDir;
  private final GenerationRunRepository runs;
  private final Duration retention;
  private final InstantSource clock;

  @Autowired
  public WorkDirSweeper(WorkDir workDir, GenerationRunRepository runs, WorkerProperties props) {
    this(workDir, runs, props.workDirRetention(), InstantSource.system());
  }

  WorkDirSweeper(
      WorkDir workDir, GenerationRunRepository runs, Duration retention, InstantSource clock) {
    this.workDir = workDir;
    this.runs = runs;
    this.retention = retention;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${emitkit.worker.work-dir-sweep-interval}")
  public void sweep() {
    if (!Files.isDirectory(workDir.root())) {
      return;
    }
    Instant cutoff = clock.instant().minus(retention);
    try (Stream<Path> children = Files.list(workDir.root())) {
      children
          .filter(dir -> RUN_ID.matcher(dir.getFileName().toString()).matches())
          .forEach(dir -> sweepRun(dir, cutoff));
    } catch (IOException e) {
      LOG.warn("could not list {}", workDir.root(), e);
    }
  }

  private void sweepRun(Path dir, Instant cutoff) {
    String runId = dir.getFileName().toString();
    try {
      if (!Files.isDirectory(dir) || Files.getLastModifiedTime(dir).toInstant().isAfter(cutoff)) {
        return;
      }
    } catch (IOException e) {
      return;
    }
    var run = runs.findById(runId);
    if (run.isPresent() && !run.get().getStatus().isTerminal()) {
      return;
    }
    workDir.delete(runId);
  }
}
