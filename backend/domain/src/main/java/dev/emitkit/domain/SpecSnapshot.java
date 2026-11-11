package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stored copy of a project's OpenAPI spec, identified by its content hash. Maps to {@code
 * spec_snapshots}. {@code apiModel} is the parsed model as JSON text, which callers parse.
 */
@Entity
@Table(name = "spec_snapshots")
public class SpecSnapshot extends BaseEntity {

  @Column(name = "project_id", nullable = false, length = 26)
  private String projectId;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "raw_content", nullable = false)
  private String rawContent;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "api_model", nullable = false)
  private String apiModel;

  @Column(name = "info_version", nullable = false, length = 255)
  private String infoVersion;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpecSnapshot() {}

  /**
   * Creates a snapshot.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param apiModel the parsed model as JSON text
   */
  public SpecSnapshot(
      String id,
      String projectId,
      String contentHash,
      String rawContent,
      String apiModel,
      String infoVersion) {
    super(id);
    this.projectId = projectId;
    this.contentHash = contentHash;
    this.rawContent = rawContent;
    this.apiModel = apiModel;
    this.infoVersion = infoVersion;
  }

  public String getProjectId() {
    return projectId;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getRawContent() {
    return rawContent;
  }

  public String getApiModel() {
    return apiModel;
  }

  public String getInfoVersion() {
    return infoVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
