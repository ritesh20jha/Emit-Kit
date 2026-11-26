package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.regex.Pattern;

/** An explicit {@code operationId} that is not lower camelCase. Synthesized ids are skipped. */
public final class OperationIdCasingRule extends AbstractLintRule {

  private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

  public OperationIdCasingRule() {
    super("operationId-casing", Severity.WARNING);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    return model.operations().stream()
        .filter(op -> !op.operationIdSynthesized())
        .filter(op -> !CAMEL_CASE.matcher(op.operationId()).matches())
        .map(op -> {
          String path = LineIndex.operationPath(op);
          return issue(
              path + ".operationId",
              lines.lineOf(path + ".operationId", path),
              "operationId '" + op.operationId() + "' is not lowerCamelCase");
        })
        .toList();
  }
}
