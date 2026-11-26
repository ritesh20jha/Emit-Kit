package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.Objects;

/** The API has no {@code info.description}. */
public final class InfoDescriptionRule extends AbstractLintRule {

  public InfoDescriptionRule() {
    super("info-description", Severity.INFO);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    if (!Objects.toString(model.info().description(), "").isBlank()) {
      return List.of();
    }
    return List.of(
        issue(
            "$.info.description",
            lines.lineOf("$.info.description", "$.info"),
            "info.description is missing"));
  }
}
