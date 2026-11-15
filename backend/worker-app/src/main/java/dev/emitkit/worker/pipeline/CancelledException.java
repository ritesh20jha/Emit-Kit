package dev.emitkit.worker.pipeline;

/** Thrown when a run is cancelled. Not an AppException, so a step cannot turn it into a failure. */
public class CancelledException extends RuntimeException {

  /** Creates the exception for the cancelled run. */
  public CancelledException(String runId) {
    super("generation run " + runId + " was cancelled");
  }
}
