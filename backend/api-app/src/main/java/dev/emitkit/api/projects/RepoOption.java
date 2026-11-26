package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A repository the signed-in user can pick when creating a project.
 *
 * @param pushedAt the last push, or null when the host has none
 */
public record RepoOption(
    @Schema(requiredMode = REQUIRED) String fullName,
    @Schema(requiredMode = REQUIRED) String owner,
    @Schema(requiredMode = REQUIRED) boolean isPrivate,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Instant pushedAt,
    @Schema(requiredMode = REQUIRED) String defaultBranch) {}
