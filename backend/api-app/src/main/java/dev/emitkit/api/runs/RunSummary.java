package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A run as it appears in a list. The duration and PR fields are null until they exist. */
public record RunSummary(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) RunStatus status,
    @Schema(requiredMode = REQUIRED) RunTrigger trigger,
    @Schema(requiredMode = REQUIRED) boolean dryRun,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String sdkVersion,
    @Schema(
            requiredMode = REQUIRED,
            types = {"integer", "null"})
        @Nullable Long durationMs,
    @Schema(
            requiredMode = REQUIRED,
            types = {"integer", "null"})
        @Nullable Integer prNumber,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String prUrl,
    @Schema(requiredMode = REQUIRED) Instant createdAt) {}
