package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;

/**
 * An operation with no {@code tags} in the document. The model fills in "default" for those, so the
 * raw document decides; an explicit tag named "default" passes.
 */
public final class OperationTagsRule extends AbstractLintRule {

  public OperationTagsRule() {
    super("operation-tags", Severity.WARNING);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    return model.operations().stream()
        .filter(op -> lines.itemCount(LineIndex.operationPath(op) + ".tags") == 0)
        .map(op -> {
          String path = LineIndex.operationPath(op);
          return issue(path + ".tags", lines.lineOf(path + ".tags", path), "operation has no tags");
        })
        .toList();
  }
}
