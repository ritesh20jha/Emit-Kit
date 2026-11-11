package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A repository the signed-in user can pick when creating a project. */
public record RepoOption(
    @Schema(requiredMode = REQUIRED) String fullName,
    @Schema(requiredMode = REQUIRED) String owner,
    @Schema(requiredMode = REQUIRED) boolean isPrivate,
    @Schema(requiredMode = REQUIRED) Instant pushedAt,
    @Schema(requiredMode = REQUIRED) String defaultBranch) {}
