package dev.emitkit.worker.sandbox;

import java.util.concurrent.CancellationException;

/** Runs one check in an isolated sandbox container. */
public interface SandboxRunner {

  /**
   * Runs {@code req} until the container exits, the timeout passes, or {@code token} is cancelled.
   * The container exits: the result holds its {@code out.tar} and {@code report.json}, each null if
   * absent. The timeout passes: the container is killed and {@code timedOut} is true. Cancellation,
   * while waiting for a permit or while running: the container is killed and the call throws {@link
   * CancellationException}. Waiting for a permit does not count against the timeout. Interrupting
   * the calling thread counts as cancellation.
   *
   * @throws SandboxInfraException when the Docker daemon, the image or a copy fails
   */
  SandboxResult run(SandboxRequest req, CancellationToken token);
}
