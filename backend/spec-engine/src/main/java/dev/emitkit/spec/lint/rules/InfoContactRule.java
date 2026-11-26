package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;

/** The document has no {@code info.contact}. */
public final class InfoContactRule extends AbstractLintRule {

  public InfoContactRule() {
    super("info-contact", Severity.INFO);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    if (lines.exists("$.info.contact")) {
      return List.of();
    }
    return List.of(issue("$.info.contact", lines.lineOf("$.info"), "info.contact is missing"));
  }
}
