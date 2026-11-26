package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@code {name}} template variable with no matching path parameter. Reported once per name. */
public final class PathParamsDefinedRule extends AbstractLintRule {

  private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}]+)}");

  public PathParamsDefinedRule() {
    super("path-params-defined", Severity.ERROR);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    var issues = new ArrayList<LintIssue>();
    Set<String> reported = new HashSet<>();
    for (Operation op : model.operations()) {
      Matcher matcher = TEMPLATE_VAR.matcher(op.path());
      while (matcher.find()) {
        String name = matcher.group(1);
        boolean declared =
            op.parameters().stream()
                .anyMatch(p -> p.in() == ParamLocation.PATH && p.name().equals(name));
        if (!declared && reported.add(op.path() + "|" + name)) {
          String path = LineIndex.pathItemPath(op.path());
          issues.add(
              issue(path, lines.line(path), "path parameter '{" + name + "}' is not declared"));
        }
      }
    }
    return issues;
  }
}
