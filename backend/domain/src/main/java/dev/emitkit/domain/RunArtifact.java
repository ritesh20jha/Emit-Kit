package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The packaged output of a generation run, with its file manifest. Maps to {@code run_artifacts}. A
 * run has at most one artifact. Its bytes are in object storage under {@code storageKey}.
 */
@Entity
@Table(name = "run_artifacts")
public class RunArtifact extends BaseEntity {

  @Column(name = "run_id", nullable = false, updatable = false, length = 26)
  private String runId;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "file_count", nullable = false)
  private int fileCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "manifest", nullable = false)
  private List<ManifestEntry> manifest;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RunArtifact() {}

  /**
   * Creates an artifact record.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param manifest one entry per file in the artifact
   * @param createdAt when the artifact was created, or {@code null} for now. Truncated to the
   *     microseconds PostgreSQL stores, so an artifact read back compares equal to this one.
   */
  public RunArtifact(
      String id,
      String runId,
      String storageKey,
      long sizeBytes,
      int fileCount,
      List<ManifestEntry> manifest,
      Instant createdAt) {
    super(id);
    this.runId = runId;
    this.storageKey = storageKey;
    this.sizeBytes = sizeBytes;
    this.fileCount = fileCount;
    this.manifest = manifest;
    this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
  }

  public String getRunId() {
    return runId;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public int getFileCount() {
    return fileCount;
  }

  public List<ManifestEntry> getManifest() {
    return manifest;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
