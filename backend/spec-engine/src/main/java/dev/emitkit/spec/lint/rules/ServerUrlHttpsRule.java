package dev.emitkit.spec.lint.rules;

import dev.emitkit.spec.lint.LineIndex;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** A server URL over plain http. Localhost and relative URLs are allowed. */
public final class ServerUrlHttpsRule extends AbstractLintRule {

  private static final Pattern PLAIN_HTTP =
      Pattern.compile("http://(?!localhost([:/]|$))", Pattern.CASE_INSENSITIVE);

  public ServerUrlHttpsRule() {
    super("server-url-https", Severity.WARNING);
  }

  @Override
  public List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines) {
    return IntStream.range(0, model.servers().size())
        .filter(i -> PLAIN_HTTP.matcher(model.servers().get(i).url()).lookingAt())
        .mapToObj(i -> {
          String server = "$.servers[" + i + "]";
          return issue(
              server + ".url",
              lines.lineOf(server + ".url", server),
              "server url '" + model.servers().get(i).url() + "' should use https");
        })
        .toList();
  }
}
