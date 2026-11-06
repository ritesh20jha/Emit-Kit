package dev.emitkit.spec.lint;

import dev.emitkit.spec.model.ApiModel;
import java.util.List;

/** One fixed v1 lint rule. Rules read the model, and the raw document through {@link LineIndex}. */
public interface LintRule {

  String id();

  Severity severity();

  List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines);
}
