package dev.emitkit.vcs;

import java.time.Instant;

/** API quota: {@code remaining} of {@code limit} requests left until {@code resetAt}. */
public record RateLimit(int limit, int remaining, Instant resetAt) {}
