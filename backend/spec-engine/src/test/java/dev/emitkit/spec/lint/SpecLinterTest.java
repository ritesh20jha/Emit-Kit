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
import java.util.Set;
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

  private static List<LintIssue> ofRule(List<LintIssue> issues, String ruleId) {
    return issues.stream().filter(i -> i.ruleId().equals(ruleId)).toList();
  }

  private static LintIssue only(List<LintIssue> issues) {
    assertThat(issues).hasSize(1);
    return issues.get(0);
  }

  /** Fail fixtures whose swagger-parser messages are part of what they exercise. */
  private static final Set<String> PARSER_MESSAGE_FIXTURES =
      Set.of("operation-operationId-unique-fail.yaml", "path-params-defined-fail.yaml");

  @ParameterizedTest
  @MethodSource("ruleIds")
  void failFixtureRaisesRule_passFixtureDoesNot(String ruleId) {
    String fail = ruleId + "-fail.yaml";
    assertThat(lint(fixture(fail))).extracting(LintIssue::ruleId).contains(ruleId);
    if (!PARSER_MESSAGE_FIXTURES.contains(fail)) {
      assertThat(lint(fixture(fail))).extracting(LintIssue::ruleId).doesNotContain("spec-parse");
    }
    assertThat(lint(fixture(ruleId + "-pass.yaml")))
        .extracting(LintIssue::ruleId)
        .doesNotContain(ruleId, "spec-parse");
  }

  @Test
  void severitiesMatchSpec() {
    assertThat(linter.rules())
        .extracting(LintRule::id, LintRule::severity)
        .containsExactly(
            tuple("operation-operationId", WARNING),
            tuple("operation-operationId-unique", ERROR),
            tuple("operation-description", WARNING),
            tuple("operation-tags", WARNING),
            tuple("path-params-defined", ERROR),
            tuple("no-unused-components", INFO),
            tuple("info-contact", INFO),
            tuple("info-description", INFO),
            tuple("operationId-casing", WARNING),
            tuple("server-url-https", WARNING));
  }

  @Test
  void issuePointsAtLineAndPath() {
    var i = only(ofRule(lint(fixture("path-params-defined-fail.yaml")), "path-params-defined"));
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
            tuple(ERROR, null),
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
    String raw = HEADER + "a: [";
    assertThat(lintWithModel(raw))
        .extracting(LintIssue::ruleId, LintIssue::severity)
        .containsExactly(tuple("spec-parse", ERROR));
    assertThat(lintWithModel(raw).get(0).message()).startsWith("could not index the document: ");
  }

  /** A valid document: one operation without tags, and one schema anchor. */
  private static final String MANY_ALIASES_HEAD =
      """
      openapi: 3.0.3
      info: {title: T, version: "1", description: d, contact: {email: a@example.com}}
      paths:
        /pets:
          get:
            operationId: listPets
            description: List pets
            responses: {"200": {description: ok}}
      components:
        schemas:
          Shared: &shared {type: object}
      """;

  /** A valid document whose schemas form a chain, each referring to the next. */
  private static final String CHAIN_HEAD =
      """
      openapi: 3.0.3
      info: {title: T, version: "1", description: d, contact: {email: a@example.com}}
      paths:
        /x:
          get:
            operationId: getX
            description: d
            tags: [t]
            responses: {"200": {description: ok, content: {application/json: {schema: {$ref: "#/components/schemas/S0"}}}}}
      components:
        schemas:
      """;

  @Test
  void validDocumentWithManyAliasesLintsNormally() {
    var yaml = new StringBuilder(MANY_ALIASES_HEAD);
    for (int i = 0; i < 55; i++) {
      yaml.append("    S%d: *shared\n".formatted(i));
    }
    String raw = yaml.toString();
    var parsed = parser.parse(raw);
    assertThat(parsed.ok()).as("errors: %s", parsed.errors()).isTrue();
    assertThat(linter.lint(parsed, raw))
        .extracting(LintIssue::ruleId)
        .contains("operation-tags")
        .doesNotContain("spec-parse");
  }

  @Test
  void parseErrorsAreReportedAlongsideRuleIssues() {
    // swagger-parser reports the undeclared {id}; the model is still built and the rules run.
    assertThat(lint(fixture("path-params-defined-fail.yaml")))
        .extracting(LintIssue::ruleId, LintIssue::severity, LintIssue::line)
        .containsExactly(tuple("path-params-defined", ERROR, 6), tuple("spec-parse", ERROR, null));
  }

  @Test
  void chainedComponentsLintInLinearTime() {
    int n = 20_000;
    var yaml = new StringBuilder(CHAIN_HEAD);
    for (int i = 0; i < n; i++) {
      String next =
          i + 1 < n ? "{$ref: \"#/components/schemas/S%d\"}".formatted(i + 1) : "{type: string}";
      yaml.append("    S%d: {type: object, properties: {next: %s}}\n".formatted(i, next));
    }
    String raw = yaml.toString();
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThat(lintWithModel(raw))
                .extracting(LintIssue::ruleId)
                .doesNotContain("no-unused-components", "spec-parse"));
  }

  @Test
  void explicitDefaultTagPasses() {
    var raw = fixture("operation-tags-pass.yaml").replace("tags: [pets]", "tags: [default]");
    assertThat(lint(raw)).extracting(LintIssue::ruleId).doesNotContain("operation-tags");
  }
}
