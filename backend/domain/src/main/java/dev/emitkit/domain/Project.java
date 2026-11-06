package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A repository that EmitKit generates SDKs for. Maps to {@code projects}. The same repository can
 * exist in several organizations, but only once per organization.
 */
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

  @Column(name = "org_id", nullable = false, length = 26)
  private String orgId;

  @Column(name = "repo_full_name", nullable = false, length = 255)
  private String repoFullName;

  @Column(name = "spec_path", nullable = false, length = 255)
  private String specPath;

  @Column(name = "default_branch", nullable = false, length = 255)
  private String defaultBranch;

  @Enumerated(EnumType.STRING)
  @Column(name = "output_mode", nullable = false, length = 16)
  private OutputMode outputMode;

  @Column(name = "output_repo_full_name", length = 255)
  private String outputRepoFullName;

  @Column(name = "webhook_id")
  private Long webhookId;

  @Column(name = "webhook_secret_enc")
  private String webhookSecretEnc;

  @Column(name = "created_by", nullable = false, length = 26)
  private String createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Project() {}

  /**
   * Creates a project.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param createdBy the id of the user who connected the repository
   */
  public Project(
      String id,
      String orgId,
      String repoFullName,
      String specPath,
      String defaultBranch,
      OutputMode outputMode,
      String createdBy) {
    super(id);
    this.orgId = orgId;
    this.repoFullName = repoFullName;
    this.specPath = specPath;
    this.defaultBranch = defaultBranch;
    this.outputMode = outputMode;
    this.createdBy = createdBy;
  }

  public String getOrgId() {
    return orgId;
  }

  public String getRepoFullName() {
    return repoFullName;
  }

  public String getSpecPath() {
    return specPath;
  }

  public String getDefaultBranch() {
    return defaultBranch;
  }

  public OutputMode getOutputMode() {
    return outputMode;
  }

  public String getOutputRepoFullName() {
    return outputRepoFullName;
  }

  public void setOutputRepoFullName(String outputRepoFullName) {
    this.outputRepoFullName = outputRepoFullName;
  }

  public Long getWebhookId() {
    return webhookId;
  }

  public void setWebhookId(Long webhookId) {
    this.webhookId = webhookId;
  }

  public String getWebhookSecretEnc() {
    return webhookSecretEnc;
  }

  public void setWebhookSecretEnc(String webhookSecretEnc) {
    this.webhookSecretEnc = webhookSecretEnc;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
