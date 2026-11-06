package dev.emitkit.spec.lint;

import static dev.emitkit.spec.lint.Severity.ERROR;
import static dev.emitkit.spec.lint.Severity.INFO;
import static dev.emitkit.spec.lint.Severity.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SpecLinterTest {

  private static final String MIXED =
      """
      openapi: 3.0.3
      info:
        title: Pets
        version: "1"
      paths:
        /pets/{id}:
          get:
            operationId: getPet
            responses: {"200": {description: ok}}
        /pets:
          get:
            operationId: listPets
            responses: {"200": {description: ok}}
      """;

  private final SpecParser parser = new SpecParser();
  private final SpecLinter linter = new SpecLinter();

  static Stream<String> ruleIds() {
    return Stream.of(
        "operation-operationId",
        "operation-operationId-unique",
        "operation-description",
        "operation-tags",
        "path-params-defined",
        "no-unused-components",
        "info-contact",
        "info-description",
        "operationId-casing",
        "server-url-https");
  }

  private static String fixture(String name) {
    String path = URI.create("/specs/lint/").resolve(name).normalize().getPath();
    try (var in = SpecLinterTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<LintIssue> lint(String raw) {
    return linter.lint(parser.parse(raw), raw);
  }

  private static LintIssue only(List<LintIssue> issues) {
    assertThat(issues).hasSize(1);
    return issues.get(0);
  }

  @ParameterizedTest
  @MethodSource("ruleIds")
  void failFixtureRaisesRule_passFixtureDoesNot(String ruleId) {
    assertThat(lint(fixture(ruleId + "-fail.yaml")))
        .extracting(LintIssue::ruleId)
        .contains(ruleId)
        .doesNotContain("spec-parse");
    assertThat(lint(fixture(ruleId + "-pass.yaml")))
        .extracting(LintIssue::ruleId)
        .doesNotContain(ruleId, "spec-parse");
  }

  @Test
  void severitiesMatchSpec() {
    assertThat(linter.rules())
        .extracting(LintRule::id, LintRule::severity)
        .contains(
            tuple("operation-operationId-unique", ERROR),
            tuple("path-params-defined", ERROR),
            tuple("no-unused-components", INFO),
            tuple("server-url-https", WARNING));
  }

  @Test
  void issuePointsAtLineAndPath() {
    var i = only(lint(fixture("path-params-defined-fail.yaml")));
    assertThat(i.jsonPath()).isEqualTo("$.paths['/pets/{id}']");
    assertThat(i.line()).isEqualTo(6);
  }

  @Test
  void parseErrors_becomeErrorIssues() {
    var issues = lint(fixture("../invalid.yaml"));
    assertThat(issues)
        .isNotEmpty()
        .allSatisfy(i -> {
          assertThat(i.ruleId()).isEqualTo("spec-parse");
          assertThat(i.severity()).isEqualTo(ERROR);
          assertThat(i.jsonPath()).isEqualTo("$");
          assertThat(i.line()).isNull();
        });
  }

  @Test
  void sortedErrorsFirst() {
    // Severity wins over line; within a severity, ascending line. Rules run in spec order.
    assertThat(lint(MIXED))
        .extracting(LintIssue::severity, LintIssue::line)
        .containsExactly(
            tuple(ERROR, 6),
            tuple(WARNING, 7),
            tuple(WARNING, 7),
            tuple(WARNING, 11),
            tuple(WARNING, 11),
            tuple(INFO, 2),
            tuple(INFO, 2));
  }

  /** Lints raw content against the model of a valid fixture, so the index is what gets tested. */
  private List<LintIssue> lintWithModel(String raw) {
    var model = parser.parse(fixture("operation-operationId-pass.yaml")).model();
    return linter.lint(new ParseResult(model, List.of(), "1", "hash", "yaml"), raw);
  }

  /** Each level holds two aliases of the level below, so 24 levels expand to 2^24 leaves. */
  private static String aliasLadder(int levels) {
    var yaml = new StringBuilder(HEADER + "ladder0: &l0 {v: 1}\n");
    for (int i = 1; i <= levels; i++) {
      yaml.append("ladder%d: &l%d {a: *l%d, b: *l%d}\n".formatted(i, i, i - 1, i - 1));
    }
    return yaml.toString();
  }

  private static final String HEADER =
      "openapi: 3.0.3\ninfo: {title: T, version: \"1\"}\npaths: {}\n";

  @Test
  void selfReferentialAnchor_isSurfacedWithoutRecursion() {
    String raw = HEADER + "loop: &x {again: *x}\n";
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThat(lintWithModel(raw))
                .extracting(LintIssue::ruleId, LintIssue::message)
                .containsExactly(
                    tuple(
                        "spec-parse",
                        "could not index the document: it contains a recursive alias")));
  }

  @Test
  void aliasLadder_isBoundedAndSurfaced() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThat(lintWithModel(aliasLadder(24)))
                .extracting(LintIssue::ruleId, LintIssue::message)
                .containsExactly(
                    tuple(
                        "spec-parse",
                        "could not index the document: it has more than 500000 nodes once aliases are expanded")));
  }

  @Test
  void composeFailure_isSurfacedInsteadOfFalsePositives() {
    // SnakeYAML refuses more than 50 aliases of a collection; the index must say so.
    String refs = "*b, ".repeat(60) + "*b";
    String raw = HEADER + "base: &b {v: 1}\nlist: [" + refs + "]\n";
    assertThat(lintWithModel(raw))
        .extracting(LintIssue::ruleId, LintIssue::severity)
        .containsExactly(tuple("spec-parse", ERROR));
    assertThat(lintWithModel(raw).get(0).message()).startsWith("could not index the document: ");
  }

  @Test
  void explicitDefaultTagPasses() {
    var raw = fixture("operation-tags-pass.yaml").replace("tags: [pets]", "tags: [default]");
    assertThat(lint(raw)).extracting(LintIssue::ruleId).doesNotContain("operation-tags");
  }
}
