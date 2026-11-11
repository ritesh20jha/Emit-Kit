package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run of the generation pipeline for a project. Maps to {@code generation_runs}. The creation
 * time can be set by the caller, so a run can be placed at a given point in the run history.
 */
@Entity
@Table(name = "generation_runs")
public class GenerationRun extends BaseEntity {

  @Column(name = "project_id", nullable = false, length = 26)
  private String projectId;

  @Column(name = "config_id", nullable = false, length = 26)
  private String configId;

  @Column(name = "spec_snapshot_id", length = 26)
  private String specSnapshotId;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 16)
  private RunTrigger trigger;

  @Column(name = "triggered_by_user_id", length = 26)
  private String triggeredByUserId;

  @Column(name = "parent_run_id", length = 26)
  private String parentRunId;

  @Column(name = "dry_run", nullable = false)
  private boolean dryRun;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private RunStatus status = RunStatus.QUEUED;

  @Enumerated(EnumType.STRING)
  @Column(name = "current_step", length = 32)
  private StepName currentStep;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "failure_message")
  private String failureMessage;

  @Column(name = "cancel_requested", nullable = false)
  private boolean cancelRequested;

  @Column(name = "attempt", nullable = false)
  private int attempt;

  /** Step name to saved output. Field initializers mirror the column default in V3__runs.sql. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "step_outputs", nullable = false)
  private String stepOutputs = "{}";

  @Column(name = "commit_sha", length = 64)
  private String commitSha;

  @Column(name = "sdk_version", length = 64)
  private String sdkVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", length = 16)
  private ChangeType changeType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "diff_summary")
  private String diffSummary;

  @Column(name = "branch_name", length = 255)
  private String branchName;

  @Column(name = "pr_url", length = 512)
  private String prUrl;

  @Column(name = "pr_number")
  private Integer prNumber;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  protected GenerationRun() {}

  /**
   * Creates a run in {@link RunStatus#QUEUED}.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param configId the project config the run uses
   * @param createdAt when the run was created, or {@code null} for now. Truncated to the
   *     microseconds PostgreSQL stores, so a run read back compares equal to this one.
   */
  public GenerationRun(
      String id,
      String projectId,
      String configId,
      RunTrigger trigger,
      boolean dryRun,
      Instant createdAt) {
    super(id);
    this.projectId = projectId;
    this.configId = configId;
    this.trigger = trigger;
    this.dryRun = dryRun;
    this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
  }

  public String getProjectId() {
    return projectId;
  }

  public String getConfigId() {
    return configId;
  }

  public String getSpecSnapshotId() {
    return specSnapshotId;
  }

  public RunTrigger getTrigger() {
    return trigger;
  }

  public String getTriggeredByUserId() {
    return triggeredByUserId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public StepName getCurrentStep() {
    return currentStep;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public boolean isCancelRequested() {
    return cancelRequested;
  }

  public int getAttempt() {
    return attempt;
  }

  public String getStepOutputs() {
    return stepOutputs;
  }

  public void setStepOutputs(String stepOutputs) {
    this.stepOutputs = stepOutputs;
  }

  public String getCommitSha() {
    return commitSha;
  }

  public String getSdkVersion() {
    return sdkVersion;
  }

  public ChangeType getChangeType() {
    return changeType;
  }

  public String getDiffSummary() {
    return diffSummary;
  }

  public void setDiffSummary(String diffSummary) {
    this.diffSummary = diffSummary;
  }

  public String getBranchName() {
    return branchName;
  }

  public String getPrUrl() {
    return prUrl;
  }

  public Integer getPrNumber() {
    return prNumber;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }
}
