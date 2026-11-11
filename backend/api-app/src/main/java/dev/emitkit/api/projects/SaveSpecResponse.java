package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The result of a spec save. {@code runId} is set when a run was started. {@code runError} is set
 * when the save went through but the run could not start.
 */
public record SaveSpecResponse(
    @Schema(requiredMode = REQUIRED) String commitSha,
    @Schema(requiredMode = REQUIRED) String newSha,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String runId,
    @Schema(
            requiredMode = REQUIRED,
            types = {"object", "null"})
        @Nullable ProblemSummary runError) {}
