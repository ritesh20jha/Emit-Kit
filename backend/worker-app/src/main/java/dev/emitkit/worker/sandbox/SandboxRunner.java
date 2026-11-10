package dev.emitkit.worker.sandbox;

import java.util.concurrent.CancellationException;

/** Runs one check in an isolated sandbox container. */
public interface SandboxRunner {

  /**
   * Runs {@code req} until the container exits, the timeout passes, or {@code token} is cancelled.
   *
   * <ul>
   *   <li>The container exits: the result holds the {@code out.tar} and {@code report.json} the
   *       check left in {@code /work}, each null if it did not write one. The exit code is not
   *       reported, so a failed check is read from the report.
   *   <li>The timeout passes: the container is killed and the result has {@code timedOut=true} with
   *       both outputs null.
   *   <li>{@code token} is cancelled, whether while waiting for a permit or while the container
   *       runs: the container is killed and the call throws {@link CancellationException}. A cancel
   *       that arrives after the outputs were read does not affect the returned result.
   * </ul>
   *
   * <p>Time spent waiting for a permit does not count against the timeout. Interrupting the calling
   * thread counts as cancellation.
   *
   * @throws SandboxInfraException when the Docker daemon, the image or a copy fails
   * @throws CancellationException when {@code token} is cancelled before the run completes
   */
  SandboxResult run(SandboxRequest req, CancellationToken token);
}
