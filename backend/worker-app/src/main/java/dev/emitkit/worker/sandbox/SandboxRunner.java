package dev.emitkit.worker.sandbox;

import java.util.concurrent.CancellationException;

/** Runs one check in an isolated sandbox container. */
public interface SandboxRunner {

  /**
   * Runs {@code req} until it exits, times out, or {@code token} is cancelled. A timeout kills the
   * container and sets {@code timedOut}. Cancellation kills it and throws {@link
   * CancellationException}. Waiting for a permit does not count against the timeout; interrupting
   * the caller counts as cancellation.
   *
   * @throws SandboxInfraException when the Docker daemon, the image or a copy fails
   */
  SandboxResult run(SandboxRequest req, CancellationToken token);
}
