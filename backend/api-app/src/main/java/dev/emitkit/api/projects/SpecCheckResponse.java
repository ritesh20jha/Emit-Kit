package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The result of checking a spec file in a repository before a project is created. {@code title} and
 * {@code version} come from the spec's info block and are null when it has none.
 */
public record SpecCheckResponse(
    @Schema(requiredMode = REQUIRED) boolean found,
    @Schema(requiredMode = REQUIRED) boolean valid,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String title,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String version,
    @Schema(requiredMode = REQUIRED) List<String> errors) {}
