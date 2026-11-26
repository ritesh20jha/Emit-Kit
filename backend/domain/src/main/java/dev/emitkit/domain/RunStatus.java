package dev.emitkit.domain;

/** Where a generation run is in its lifecycle. Stored by name in {@code generation_runs.status}. */
public enum RunStatus {
  QUEUED,
  RUNNING,
  AWAITING_APPROVAL,
  SUCCESS,
  FAILED,
  CANCELLED;

  /** Whether the run has finished and will not change again. */
  public boolean isTerminal() {
    return this == SUCCESS || this == FAILED || this == CANCELLED;
  }
}
