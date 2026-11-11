package dev.emitkit.vcs.ratelimit;

import java.time.Duration;

/** Waits for a duration. Injected so that tests need not sleep for real. */
@FunctionalInterface
public interface Sleeper {

  /** Waits on the current thread with {@link Thread#sleep(Duration)}. */
  Sleeper SYSTEM =
      duration -> {
        try {
          Thread.sleep(duration);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while waiting", e);
        }
      };

  /** Waits for {@code duration}. */
  void sleep(Duration duration);
}
