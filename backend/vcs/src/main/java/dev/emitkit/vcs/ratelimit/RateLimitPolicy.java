package dev.emitkit.vcs.ratelimit;

/** How a client behaves when the stored quota of its user is low. */
public enum RateLimitPolicy {

  /** A user is waiting on the result: fail fast with the reset time instead of waiting. */
  INTERACTIVE,

  /** No user is waiting: wait for a reset that is at most five minutes away, otherwise fail. */
  BACKGROUND
}
