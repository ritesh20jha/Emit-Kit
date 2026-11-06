package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.Objects;

/** An operation with neither a summary nor a description. */
public final class OperationDescriptionRule extends AbstractLintRule {

  public OperationDescriptionRule() {
    super("operation-description", Severity.WARNING);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    return model.operations().stream()
        .filter(op ->
            Objects.toString(op.summary(), "").isBlank()
                && Objects.toString(op.description(), "").isBlank())
        .map(op -> {
          String path = LineIndex.operationPath(op);
          return issue(
              path,
              lines.line(path),
              op.method() + " " + op.path() + " has no summary or description");
        })
        .toList();
  }
}
