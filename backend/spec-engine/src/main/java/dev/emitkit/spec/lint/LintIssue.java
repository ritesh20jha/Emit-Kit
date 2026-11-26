package dev.emitkit.spec.lint;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One finding. {@code line} is 1-based and null when the document has no such node. */
public record LintIssue(
    @Schema(requiredMode = REQUIRED) String ruleId,
    @Schema(requiredMode = REQUIRED) Severity severity,
    @Schema(requiredMode = REQUIRED) String jsonPath,
    @Schema(
            requiredMode = REQUIRED,
            types = {"integer", "null"})
        @Nullable Integer line,
    @Schema(requiredMode = REQUIRED) String message) {}
