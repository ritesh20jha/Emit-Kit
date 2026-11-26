package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One finding of the spec linter for a run. Maps to {@code lint_results}. */
@Entity
@Table(name = "lint_results")
public class LintResult extends BaseEntity {

  @Column(name = "run_id", nullable = false, updatable = false, length = 26)
  private String runId;

  @Column(name = "rule_id", nullable = false, updatable = false, length = 64)
  private String ruleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, updatable = false, length = 16)
  private Severity severity;

  /** Location of the finding in the spec, as a JSON path such as {@code $.paths./pets}. */
  @Column(name = "json_path", nullable = false, updatable = false)
  private String jsonPath;

  @Column(name = "message", nullable = false, updatable = false)
  private String message;

  protected LintResult() {}

  /**
   * Creates a finding.
   *
   * @param id the ULID to use, or {@code null} to generate one
   */
  public LintResult(
      String id, String runId, String ruleId, Severity severity, String jsonPath, String message) {
    super(id);
    this.runId = runId;
    this.ruleId = ruleId;
    this.severity = severity;
    this.jsonPath = jsonPath;
    this.message = message;
  }

  public String getRunId() {
    return runId;
  }

  public String getRuleId() {
    return ruleId;
  }

  public Severity getSeverity() {
    return severity;
  }

  public String getJsonPath() {
    return jsonPath;
  }

  public String getMessage() {
    return message;
  }
}
