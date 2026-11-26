package dev.emitkit.worker.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A one-way cancellation flag shared by a job and its sandbox run. Thread-safe. Each {@link
 * #onCancel} callback runs once, on cancel or immediately if already cancelled.
 */
public final class CancellationToken {

  private static final Logger log = LoggerFactory.getLogger(CancellationToken.class);

  private final Object lock = new Object();
  private final List<Runnable> callbacks = new ArrayList<>();
  private boolean cancelled;

  /** Cancels the token and runs the pending callbacks. Later calls do nothing. */
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

  /** Registers {@code callback} to run on cancel, or runs it now if already cancelled. */
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
