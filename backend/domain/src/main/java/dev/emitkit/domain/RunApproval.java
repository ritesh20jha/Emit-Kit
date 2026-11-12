package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The approval gate of a run. Maps to {@code run_approvals}. Created pending, with no decision;
 * {@link #decide} records the reviewer's answer. A run has at most one approval.
 */
@Entity
@Table(name = "run_approvals")
public class RunApproval extends BaseEntity {

  @Column(name = "run_id", nullable = false, updatable = false, length = 26)
  private String runId;

  @Column(name = "decided_by", length = 26)
  private String decidedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision", length = 16)
  private Decision decision;

  @Column(name = "comment")
  private String comment;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  protected RunApproval() {}

  /**
   * Creates a pending approval for a run.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param createdAt when the approval was requested, or {@code null} for now. Truncated to the
   *     microseconds PostgreSQL stores.
   */
  public RunApproval(String id, String runId, Instant createdAt) {
    super(id);
    this.runId = runId;
    this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Records the reviewer's decision. Called once, when the run is approved or rejected. {@code
   * decidedAt} is truncated to microseconds, as PostgreSQL stores it.
   */
  public void decide(String decidedBy, Decision decision, String comment, Instant decidedAt) {
    this.decidedBy = decidedBy;
    this.decision = decision;
    this.comment = comment;
    this.decidedAt = decidedAt.truncatedTo(ChronoUnit.MICROS);
  }

  public String getRunId() {
    return runId;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Decision getDecision() {
    return decision;
  }

  public String getComment() {
    return comment;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}
