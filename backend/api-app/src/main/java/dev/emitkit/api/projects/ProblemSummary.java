package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** The stable code and human detail of an error, as the Problem Details body carries them. */
public record ProblemSummary(
    @Schema(requiredMode = REQUIRED) String code, @Schema(requiredMode = REQUIRED) String detail) {}
