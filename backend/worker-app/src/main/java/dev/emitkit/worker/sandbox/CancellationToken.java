package dev.emitkit.worker.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A one-way cancellation flag shared between a job and the sandbox run it started. Thread-safe.
 *
 * <p>Callbacks registered with {@link #onCancel} run once: on the thread that calls {@link
 * #cancel}, or immediately if the token is already cancelled. A callback that throws is logged and
 * does not stop the others.
 */
public final class CancellationToken {

  private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);

  private final Object lock = new Object();
  private final List<Runnable> callbacks = new ArrayList<>();
  private boolean cancelled;

  /** Marks the token cancelled and runs the pending callbacks. Calls after the first do nothing. */
  public void cancel() {
    List<Runnable> toRun;
    synchronized (lock) {
      if (cancelled) {
        return;
      }
      cancelled = true;
      toRun = List.copyOf(callbacks);
      callbacks.clear();
    }
    toRun.forEach(CancellationToken::runSafely);
  }

  /** Whether {@link #cancel} has been called. */
  public boolean isCancelled() {
    synchronized (lock) {
      return cancelled;
    }
  }

  /**
   * Registers {@code callback} to run on cancel, or runs it now if the token is already cancelled.
   */
  public void onCancel(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    synchronized (lock) {
      if (!cancelled) {
        callbacks.add(callback);
        return;
      }
    }
    runSafely(callback);
  }

  private static void runSafely(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      log.warn("cancellation callback failed", e);
    }
  }
}
