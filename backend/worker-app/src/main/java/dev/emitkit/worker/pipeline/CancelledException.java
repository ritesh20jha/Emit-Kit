package dev.emitkit.worker.pipeline;

/**
 * Thrown when a run is cancelled. It is not an {@link dev.emitkit.common.error.AppException}, so a
 * step cannot turn it into a failure. The worker cleans up when it sees it.
 */
public class CancelledException extends RuntimeException {

  /** Creates the exception for the cancelled run. */
  public CancelledException(String runId) {
    super("generation run " + runId + " was cancelled");
  }
}
