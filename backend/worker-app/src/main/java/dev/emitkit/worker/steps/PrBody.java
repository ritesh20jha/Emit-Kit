package dev.emitkit.worker.steps;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.SeverityCount;
import dev.emitkit.spec.diff.Change;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders the pull request body: the changelog from the spec diff, then the lint summary and a link
 * to the run (spec v1 §5.1 step 10). The body is markdown, so nothing is HTML-escaped.
 */
final class PrBody {

  private static final Mustache.Compiler COMPILER =
      Mustache.compiler().escapeHTML(false).strictSections(true);
  private static final Template TEMPLATE = load("pr-body.mustache");

  private PrBody() {}

  /** One changelog line. Breaking lines are listed first. */
  record Line(boolean breaking, String text) {}

  /** The body of the pull request for a run with this diff and these lint findings. */
  static String render(SpecDiff diff, List<SeverityCount> lint, String runUrl) {
    List<Line> changes = lines(diff);
    return TEMPLATE.execute(
        new View(
            !changes.isEmpty(),
            changes,
            count(lint, Severity.ERROR),
            count(lint, Severity.WARNING),
            count(lint, Severity.INFO),
            runUrl));
  }

  /**
   * The changelog lines of a diff: removed operations and breaking changes first, then the other
   * changes in the order the diff lists them.
   */
  static List<Line> lines(SpecDiff diff) {
    List<Line> lines = new ArrayList<>();
    for (OpRef op : diff.removed()) {
      lines.add(new Line(true, "Breaking: `" + endpoint(op) + "` removed"));
    }
    diff.modified()
        .forEach(modified ->
            modified
                .changes()
                .forEach(change ->
                    lines.add(
                        new Line(
                            change.breaking(),
                            (change.breaking() ? "Breaking: " : "Changed: ")
                                + "`"
                                + endpoint(modified.op())
                                + "` "
                                + describe(change)))));
    for (OpRef op : diff.added()) {
      lines.add(new Line(false, "Added: `" + endpoint(op) + "`"));
    }
    lines.sort(Comparator.comparing(Line::breaking).reversed());
    return lines;
  }

  /**
   * The changelog stored with a released version: the lines of the body's changes section, one
   * bullet per line, or a sentence saying there were none.
   */
  static String changelog(SpecDiff diff) {
    List<Line> changes = lines(diff);
    if (changes.isEmpty()) {
      return "No API changes were recorded for this run.";
    }
    return changes.stream().map(line -> "- " + line.text()).collect(Collectors.joining("\n"));
  }

  private static String endpoint(OpRef op) {
    return op.method() + " " + op.path() + " (" + op.operationId() + ")";
  }

  /** For example {@code param removed at query.limit}. */
  private static String describe(Change change) {
    return change.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ')
        + " at `"
        + change.location()
        + "`";
  }

  private static long count(List<SeverityCount> lint, Severity severity) {
    return lint.stream()
        .filter(count -> count.severity() == severity)
        .mapToLong(SeverityCount::count)
        .sum();
  }

  /** Compiles a template from the classpath, under {@code templates/}. */
  static Template load(String name) {
    String path = "templates/" + name;
    try (InputStream in = PrBody.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("template not on classpath: " + path);
      }
      return COMPILER.compile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read template " + path, e);
    }
  }

  /** Values rendered by {@code pr-body.mustache}. */
  record View(
      boolean hasChanges,
      List<Line> changes,
      long errors,
      long warnings,
      long infos,
      String runUrl) {}
}
