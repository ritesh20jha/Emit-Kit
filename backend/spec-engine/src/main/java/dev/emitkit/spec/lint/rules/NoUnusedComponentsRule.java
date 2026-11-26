package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A component schema that no operation reaches, directly or through other components. The model
 * carries no component schemas, so the refs are followed in the raw document.
 */
public final class NoUnusedComponentsRule extends AbstractLintRule {

  private static final String SCHEMAS = "$.components.schemas";

  public NoUnusedComponentsRule() {
    super("no-unused-components", Severity.INFO);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    Set<String> reached = new HashSet<>();
    var pending = new ArrayDeque<String>();
    pending.push("$.paths");
    while (!pending.isEmpty()) {
      for (String ref : lines.refsUnder(pending.pop())) {
        if (ref.startsWith("#/") && reached.add(ref)) {
          pending.push(LineIndex.jsonPathOfRef(ref));
        }
      }
    }
    return lines.keys(SCHEMAS).stream()
        .filter(name -> !reached.contains("#/components/schemas/" + name))
        .map(name -> {
          String path = LineIndex.member(SCHEMAS, name);
          return issue(
              path, lines.line(path), "schema '" + name + "' is not referenced by any operation");
        })
        .toList();
  }
}
