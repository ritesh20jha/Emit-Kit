package dev.emitkit.api.me;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** The VCS API quota left for the signed-in user and when it resets. */
public record QuotaResponse(
    @Schema(requiredMode = REQUIRED) int limit,
    @Schema(requiredMode = REQUIRED) int remaining,
    @Schema(requiredMode = REQUIRED) Instant resetAt) {}
