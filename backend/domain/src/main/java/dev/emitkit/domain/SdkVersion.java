package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A released version of a project's generated SDK, with the run that produced it. Maps to {@code
 * sdk_versions}. A project has each version at most once.
 */
@Entity
@Table(name = "sdk_versions")
public class SdkVersion extends BaseEntity {

  @Column(name = "project_id", nullable = false, updatable = false, length = 26)
  private String projectId;

  @Column(name = "version", nullable = false, updatable = false, length = 64)
  private String version;

  @Column(name = "run_id", nullable = false, updatable = false, length = 26)
  private String runId;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", nullable = false, updatable = false, length = 16)
  private ChangeType changeType;

  @Column(name = "changelog", nullable = false, updatable = false)
  private String changelog;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SdkVersion() {}

  /**
   * Creates a version record.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param createdAt when the version was released, or {@code null} for now. Truncated to the
   *     microseconds PostgreSQL stores.
   */
  public SdkVersion(
      String id,
      String projectId,
      String version,
      String runId,
      ChangeType changeType,
      String changelog,
      Instant createdAt) {
    super(id);
    this.projectId = projectId;
    this.version = version;
    this.runId = runId;
    this.changeType = changeType;
    this.changelog = changelog;
    this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
  }

  public String getProjectId() {
    return projectId;
  }

  public String getVersion() {
    return version;
  }

  public String getRunId() {
    return runId;
  }

  public ChangeType getChangeType() {
    return changeType;
  }

  public String getChangelog() {
    return changelog;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
