# PRJ-06 · Lint endpoint, snapshots, spec diff

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the linter for the editor and the differ for the spec diff page.

**Lane:** API · **Depends on:** [SPEC-04](SPEC-04-linter.md), [SPEC-06](SPEC-06-differ-schemas-semver.md), [DB-03](DB-03-runs-schema.md), [CON-02](CON-02-contract-projects.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/prj-06`

**Spec:** v1 §8 (`POST /lint`, snapshots, diff), §5.3, §5.4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/spec/LintController.java`, `SpecHistoryController.java`, `SpecHistoryService.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/spec/PRJ06LintDiffIT.java`

## Interfaces

- **Produces:**
- `POST /api/v1/lint` (any signed-in user; no org): `SpecLinter.lint(SpecParser.parse(content), content)`. Rate-limited to 30/min per user (Redis counter) → 429 `RATE_LIMITED`.
- `GET /projects/{id}/spec/snapshots` newest first, max 100.
- `GET /projects/{id}/spec/diff?from&to`: both snapshots must belong to the project (else 404); diff from stored `api_model` JSON (deserialize with `ApiModelJson.fromJson` from SPEC-03).

## Tasks

### Task 1: Lint endpoint, snapshots, spec diff

- [ ] **Step 1: Write the failing test**

```java
@Test void lint_invalidYaml_returnsSpecParseIssue() { }
@Test void lint_returnsLineNumbers() { }
@Test void lint_rateLimited_429() { }
@Test void diff_matchesDiffer() { assertThat(getDiff(s1, s2)).isEqualTo(json(new SpecDiffer().diff(model(s1), model(s2)))); }
@Test void diff_snapshotOfOtherProject_404() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ06*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ06*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): lint endpoint, snapshots, spec diff"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-06`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
