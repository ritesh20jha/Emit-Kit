package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import java.util.List;

/** An operation without an {@code operationId}; the model synthesized one. */
public final class OperationIdRule extends AbstractLintRule {

  public OperationIdRule() {
    super("operation-operationId", Severity.WARNING);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    return model.operations().stream()
        .filter(Operation::operationIdSynthesized)
        .map(op -> {
          String path = LineIndex.operationPath(op);
          return issue(
              path + ".operationId",
              lines.lineOf(path + ".operationId", path),
              op.method() + " " + op.path() + " has no operationId");
        })
        .toList();
  }
}
