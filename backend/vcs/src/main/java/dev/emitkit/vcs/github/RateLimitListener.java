package dev.emitkit.vcs.github;

import java.time.Instant;

/** Receives the rate-limit headers of every GitHub response (VCS-05 records quota per token). */
public interface RateLimitListener {

  /** Called once per response that carries rate-limit headers. */
  void onHeaders(int remaining, Instant reset);
}
