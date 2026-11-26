package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.ChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A published SDK version of a project and the run that produced it. */
public record VersionResponse(
    @Schema(requiredMode = REQUIRED) String version,
    @Schema(requiredMode = REQUIRED) ChangeType changeType,
    @Schema(requiredMode = REQUIRED) String runId,
    @Schema(requiredMode = REQUIRED) String changelog,
    @Schema(requiredMode = REQUIRED) Instant createdAt) {}
