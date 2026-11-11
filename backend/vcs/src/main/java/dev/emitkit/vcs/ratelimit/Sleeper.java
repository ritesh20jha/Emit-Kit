package dev.emitkit.vcs.ratelimit;

import java.time.Duration;

/** Waits for a duration. Injected so that tests need not sleep for real. */
@FunctionalInterface
public interface Sleeper {

  /** Waits on the current thread with {@link Thread#sleep(Duration)}. */
  Sleeper SYSTEM = Thread::sleep;

  /**
   * Waits for {@code duration}.
   *
   * @throws InterruptedException if the waiting thread is interrupted; the caller decides how to
   *     react
   */
  void sleep(Duration duration) throws InterruptedException;
}
