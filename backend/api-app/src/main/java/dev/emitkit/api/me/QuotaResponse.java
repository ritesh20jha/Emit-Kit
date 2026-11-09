package dev.emitkit.api.me;

import java.time.Instant;

/** The VCS API quota left for the signed-in user and when it resets. */
public record QuotaResponse(int limit, int remaining, Instant resetAt) {}
