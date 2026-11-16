package dev.emitkit.api.jobs;

import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.storage.ArtifactStorage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention (v1 section 4). Run log lines older than 90 days are deleted in batches. Run
 * artifacts older than 30 days are removed from storage, then their rows are deleted, so a failed
 * storage delete leaves the row for the next run. Run rows are kept.
 */
@Component
public class RetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);
  private static final Duration LOG_RETENTION = Duration.ofDays(90);
  private static final Duration ARTIFACT_RETENTION = Duration.ofDays(30);
  private static final int LOG_BATCH = 10_000;

  private final JdbcClient jdbc;
  private final RunArtifactRepository artifacts;
  private final ArtifactStorage storage;

  RetentionJob(JdbcClient jdbc, RunArtifactRepository artifacts, ArtifactStorage storage) {
    this.jdbc = jdbc;
    this.artifacts = artifacts;
    this.storage = storage;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  @SchedulerLock(name = "retention", lockAtMostFor = "PT30M")
  public void run() {
    Instant now = Instant.now();
    long logLines = deleteLogLinesBefore(now.minus(LOG_RETENTION));
    long artifactCount = deleteArtifactsBefore(now.minus(ARTIFACT_RETENTION));
    LOG.info("retention deleted {} run log lines and {} artifacts", logLines, artifactCount);
  }

  private long deleteLogLinesBefore(Instant cutoff) {
    long total = 0;
    int deleted;
    do {
      deleted =
          jdbc.sql(
                  "delete from generation_run_logs where id in"
                      + " (select id from generation_run_logs where ts < :cutoff limit :batch)")
              .param("cutoff", Timestamp.from(cutoff))
              .param("batch", LOG_BATCH)
              .update();
      total += deleted;
    } while (deleted == LOG_BATCH);
    return total;
  }

  private long deleteArtifactsBefore(Instant cutoff) {
    long total = 0;
    List<RunArtifact> batch = artifacts.findTop100ByCreatedAtBefore(cutoff);
    while (!batch.isEmpty()) {
      for (RunArtifact artifact : batch) {
        storage.delete(artifact.getStorageKey());
        artifacts.delete(artifact);
      }
      total += batch.size();
      batch = artifacts.findTop100ByCreatedAtBefore(cutoff);
    }
    return total;
  }
}
