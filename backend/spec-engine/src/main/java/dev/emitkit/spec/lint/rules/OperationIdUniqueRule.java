package dev.emitkit.spec.lint.rules;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.Map;

/** Two operations with the same explicit {@code operationId}. Synthesized ids never count. */
public final class OperationIdUniqueRule extends AbstractLintRule {

  public OperationIdUniqueRule() {
    super("operation-operationId-unique", Severity.ERROR);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    // The raw id of each explicit operation, read from the document.
    List<String> idPaths =
        model.operations().stream()
            .filter(op -> !op.operationIdSynthesized())
            .map(op -> LineIndex.operationPath(op) + ".operationId")
            .filter(path -> lines.scalar(path) != null)
            .toList();
    Map<String, Long> counts = idPaths.stream().collect(groupingBy(lines::scalar, counting()));
    return idPaths.stream()
        .filter(path -> counts.get(lines.scalar(path)) > 1)
        .map(path ->
            issue(
                path,
                lines.line(path),
                "operationId '" + lines.scalar(path) + "' is used by more than one operation"))
        .toList();
  }
}
