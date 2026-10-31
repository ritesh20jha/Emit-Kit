# SPEC-04 · Linter

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the fixed v1 lint rule set over a parsed spec and report issues with a JSON path and a line number.

**Lane:** SPEC · **Depends on:** [SPEC-02](SPEC-02-parser-operations.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/spec-04`

**Spec:** v1 §5.3

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/lint/SpecLinter.java`, `LintIssue.java`, `Severity.java`, `LintRule.java`, `LineIndex.java`, and one class per rule under `lint/rules/`
- Create: `backend/spec-engine/src/test/resources/specs/lint/` — `{ruleId}-pass.yaml` and `{ruleId}-fail.yaml` per rule
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/lint/SpecLinterTest.java`

## Interfaces

- **Produces:**
```java
public enum Severity { ERROR, WARNING, INFO }
public record LintIssue(String ruleId, Severity severity, String jsonPath, @Nullable Integer line, String message) {}
public interface LintRule { String id(); Severity severity(); List<LintIssue> check(ApiModel model, String rawContent, LineIndex lines); }
public final class SpecLinter {
  public List<LintIssue> lint(ParseResult parsed, String rawContent);   // parse errors → one ERROR issue "spec-parse" each
}
```
Rule ids and severities (exact, v1 §5.3): `operation-operationId` WARNING, `operation-operationId-unique` ERROR, `operation-description` WARNING, `operation-tags` WARNING, `path-params-defined` ERROR, `no-unused-components` INFO, `info-contact` INFO, `info-description` INFO, `operationId-casing` WARNING, `server-url-https` WARNING.
- `jsonPath` style: `$.paths['/pets/{id}'].get.operationId`.
- `LineIndex` builds a JSON-path → line map from the raw YAML/JSON using SnakeYAML node marks (works for JSON too). Line is 1-based; null if not found.
- Output sorted by severity (ERROR first), then line.
- `operation-operationId-unique` needs the raw ids, so it reads them via `LineIndex` from the raw document (the model already de-dupes nothing, but synthesized ids are excluded).

## Tasks

### Task 1: Rules

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @MethodSource("ruleIds")
void failFixtureRaisesRule_passFixtureDoesNot(String ruleId) {
  assertThat(lint(ruleId + "-fail.yaml")).extracting(LintIssue::ruleId).contains(ruleId);
  assertThat(lint(ruleId + "-pass.yaml")).extracting(LintIssue::ruleId).doesNotContain(ruleId);
}
@Test void severitiesMatchSpec() {
  assertThat(linter.rules()).extracting(LintRule::id, LintRule::severity).contains(
      tuple("operation-operationId-unique", ERROR), tuple("path-params-defined", ERROR),
      tuple("no-unused-components", INFO), tuple("server-url-https", WARNING));
}
@Test void issuePointsAtLineAndPath() {
  var i = only(lint("path-params-defined-fail.yaml"));   // /pets/{id} with no id param, at line 6
  assertThat(i.jsonPath()).isEqualTo("$.paths['/pets/{id}']"); assertThat(i.line()).isEqualTo(6);
}
@Test void parseErrors_becomeErrorIssues() { assertThat(lint("../invalid.yaml")).extracting(LintIssue::ruleId).contains("spec-parse"); }
@Test void sortedErrorsFirst() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*SpecLinterTest'`

- [ ] **Step 3: Implement** — one class per rule; `operationId-casing` uses `^[a-z][a-zA-Z0-9]*$`; `server-url-https` ignores `http://localhost` and relative URLs; `no-unused-components` walks all `RefSchema` names reachable from operations.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*SpecLinterTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): v1 lint rules"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
