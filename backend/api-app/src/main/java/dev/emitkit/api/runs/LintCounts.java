package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many lint issues a run's spec has, by severity. */
public record LintCounts(
    @Schema(requiredMode = REQUIRED) int errors,
    @Schema(requiredMode = REQUIRED) int warnings,
    @Schema(requiredMode = REQUIRED) int infos) {}
