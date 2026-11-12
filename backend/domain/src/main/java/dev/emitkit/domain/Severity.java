package dev.emitkit.domain;

/** How serious a lint finding is. Stored by name in {@code lint_results.severity}. */
public enum Severity {
  ERROR,
  WARNING,
  INFO
}
