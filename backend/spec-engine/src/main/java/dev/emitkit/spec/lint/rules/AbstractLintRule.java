package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.LintRule;
import dev.emitkit.spec.lint.Severity;
import org.jspecify.annotations.Nullable;

/** Holds the id and severity of a rule and builds its issues. */
public abstract class AbstractLintRule implements LintRule {

  private final String id;
  private final Severity severity;

  protected AbstractLintRule(String id, Severity severity) {
    this.id = id;
    this.severity = severity;
  }

  @Override
  public final String id() {
    return id;
  }

  @Override
  public final Severity severity() {
    return severity;
  }

  protected final LintIssue issue(String jsonPath, @Nullable Integer line, String message) {
    return new LintIssue(id, severity, jsonPath, line, message);
  }
}
