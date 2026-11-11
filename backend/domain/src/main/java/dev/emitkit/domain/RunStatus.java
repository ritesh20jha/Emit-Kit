package dev.emitkit.domain;

/**
 * Where a generation run is in its life. Stored by name in the runs table. The three end states are
 * terminal: a run in one of them never changes status again.
 */
public enum RunStatus {
  QUEUED,
  RUNNING,
  AWAITING_APPROVAL,
  SUCCESS,
  FAILED,
  CANCELLED;

  /** True for SUCCESS, FAILED and CANCELLED. */
  public boolean isTerminal() {
    return this == SUCCESS || this == FAILED || this == CANCELLED;
  }
}
