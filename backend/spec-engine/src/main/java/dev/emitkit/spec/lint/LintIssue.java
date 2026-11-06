package dev.emitkit.spec.lint;

import org.jspecify.annotations.Nullable;

/** One finding. {@code line} is 1-based and null when the document has no such node. */
public record LintIssue(
    String ruleId, Severity severity, String jsonPath, @Nullable Integer line, String message) {}
