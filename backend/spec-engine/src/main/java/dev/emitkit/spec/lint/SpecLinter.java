package dev.emitkit.spec.lint;

import dev.emitkit.spec.lint.rules.InfoContactRule;
import dev.emitkit.spec.lint.rules.InfoDescriptionRule;
import dev.emitkit.spec.lint.rules.NoUnusedComponentsRule;
import dev.emitkit.spec.lint.rules.OperationDescriptionRule;
import dev.emitkit.spec.lint.rules.OperationIdCasingRule;
import dev.emitkit.spec.lint.rules.OperationIdRule;
import dev.emitkit.spec.lint.rules.OperationIdUniqueRule;
import dev.emitkit.spec.lint.rules.OperationTagsRule;
import dev.emitkit.spec.lint.rules.PathParamsDefinedRule;
import dev.emitkit.spec.lint.rules.ServerUrlHttpsRule;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.parse.ParseResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runs the fixed v1 rule set. Issues come out by severity (ERROR first), then by line. */
public final class SpecLinter {

  private static final List<LintRule> RULES =
      List.of(
          new OperationIdRule(),
          new OperationIdUniqueRule(),
          new OperationDescriptionRule(),
          new OperationTagsRule(),
          new PathParamsDefinedRule(),
          new NoUnusedComponentsRule(),
          new InfoContactRule(),
          new InfoDescriptionRule(),
          new OperationIdCasingRule(),
          new ServerUrlHttpsRule());

  private static final Comparator<LintIssue> ORDER =
      Comparator.comparing(LintIssue::severity)
          .thenComparing(LintIssue::line, Comparator.nullsLast(Comparator.naturalOrder()));

  /** The v1 rules, in the order of the spec. */
  public List<LintRule> rules() {
    return RULES;
  }

  /**
   * Lints a parsed document. Every parse error becomes an ERROR {@code spec-parse} issue. The rules
   * run when there is a model, unless the document cannot be indexed, which stops them.
   */
  public List<LintIssue> lint(ParseResult parsed, String rawContent) {
    var issues = new ArrayList<LintIssue>();
    parsed.errors().forEach(error -> issues.add(parseIssue(error)));
    ApiModel model = parsed.model();
    if (model == null) {
      return List.copyOf(issues);
    }
    LineIndex lines = LineIndex.of(rawContent);
    if (lines.failure() != null) {
      // Without a complete index, raw-document rules would report false positives: stop here.
      issues.add(parseIssue(lines.failure()));
    } else {
      RULES.forEach(rule -> issues.addAll(rule.check(model, rawContent, lines)));
    }
    issues.sort(ORDER);
    return List.copyOf(issues);
  }

  private static LintIssue parseIssue(String message) {
    return new LintIssue("spec-parse", Severity.ERROR, "$", null, message);
  }
}
