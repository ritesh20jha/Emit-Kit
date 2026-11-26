package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.OutputMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One project in full. {@code outputRepoFullName} is null unless the output goes to a separate
 * repository.
 */
public record ProjectDetail(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String orgId,
    @Schema(requiredMode = REQUIRED) String repoFullName,
    @Schema(requiredMode = REQUIRED) String specPath,
    @Schema(requiredMode = REQUIRED) String defaultBranch,
    @Schema(requiredMode = REQUIRED) OutputMode outputMode,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String outputRepoFullName,
    @Schema(requiredMode = REQUIRED) WebhookStatus webhook,
    @Schema(requiredMode = REQUIRED) Instant createdAt) {}
