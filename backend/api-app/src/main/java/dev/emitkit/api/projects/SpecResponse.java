package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** The current spec of a project. {@code sha} is what a save must send back as its base. */
public record SpecResponse(
    @Schema(requiredMode = REQUIRED) String content,
    @Schema(requiredMode = REQUIRED) String sha,
    @Schema(requiredMode = REQUIRED) String format) {}
