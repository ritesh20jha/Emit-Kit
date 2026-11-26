package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One log line of a generation run. Maps to {@code generation_run_logs}. Keyed by a database
 * sequence rather than a ULID: logs are high-volume and only ever read in {@code seq} order.
 */
@Entity
@Table(name = "generation_run_logs")
public class GenerationRunLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "run_id", nullable = false, length = 26)
  private String runId;

  @Column(name = "seq", nullable = false)
  private int seq;

  @Column(name = "ts", nullable = false)
  private Instant ts;

  @Column(name = "level", nullable = false, length = 16)
  private String level;

  @Enumerated(EnumType.STRING)
  @Column(name = "step", length = 32)
  private StepName step;

  @Column(name = "message", nullable = false)
  private String message;

  protected GenerationRunLog() {}

  /**
   * Creates a log line. The id is assigned by the database when the line is saved.
   *
   * @param step the step that wrote the line, or {@code null} for a run-level line
   */
  public GenerationRunLog(
      String runId, int seq, Instant ts, String level, StepName step, String message) {
    this.runId = runId;
    this.seq = seq;
    this.ts = ts;
    this.level = level;
    this.step = step;
    this.message = message;
  }

  public Long getId() {
    return id;
  }

  public String getRunId() {
    return runId;
  }

  public int getSeq() {
    return seq;
  }

  public Instant getTs() {
    return ts;
  }

  public String getLevel() {
    return level;
  }

  public StepName getStep() {
    return step;
  }

  public String getMessage() {
    return message;
  }
}
