package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.RunStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A project as it appears in a list. The last-run fields are null until a run has happened. */
public record ProjectSummary(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String repoFullName,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable RunStatus lastRunStatus,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Instant lastRunAt,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String currentVersion) {}
