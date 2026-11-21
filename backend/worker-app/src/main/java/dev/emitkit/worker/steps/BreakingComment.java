package dev.emitkit.worker.steps;

import com.samskivert.mustache.Template;
import dev.emitkit.spec.diff.SpecDiff;
import java.util.List;

/** Renders the comment posted on a pull request whose spec diff has breaking changes. */
final class BreakingComment {

  private static final Template TEMPLATE = PrBody.load("breaking-comment.mustache");

  private BreakingComment() {}

  /** The comment: the breaking lines of the diff and a link to the run. */
  static String render(SpecDiff diff, String runUrl) {
    List<PrBody.Line> breaking = PrBody.lines(diff).stream().filter(PrBody.Line::breaking).toList();
    return TEMPLATE.execute(new View(breaking, runUrl));
  }

  /** Values rendered by {@code breaking-comment.mustache}. */
  record View(List<PrBody.Line> changes, String runUrl) {}
}
