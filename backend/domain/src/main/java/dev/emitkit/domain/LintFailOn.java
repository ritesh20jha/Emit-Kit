package dev.emitkit.domain;

/**
 * Lowest lint severity that fails a run. Stored by name in {@code project_configs.lint_fail_on}.
 */
public enum LintFailOn {
  NEVER,
  ERROR,
  WARNING
}
