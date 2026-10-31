# WRK-04 · Steps: LintSpec + DiffSpec + CalcVersion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lint the snapshot, diff it against the last successful real run, and compute the next version.

**Lane:** WRK · **Depends on:** [WRK-03](WRK-03-fetch-parse.md), [SPEC-04](SPEC-04-linter.md), [SPEC-06](SPEC-06-differ-schemas-semver.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-04`

**Spec:** v1 §5.1 steps 3–5, §5.5

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/LintSpecStep.java`, `DiffSpecStep.java`, `CalcVersionStep.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK04LintDiffVersionTest.java`

## Interfaces

- **Produces:**
- `LintSpec`: save all issues to `lint_results`; `lint_fail_on=ERROR` and any ERROR → `LINT_FAILED`; `WARNING` and any ERROR or WARNING → `LINT_FAILED`; message "N lint errors" with the first 3 rule ids. Idempotent: delete existing rows for the run first.
- `DiffSpec`: baseline = snapshot of `findLastSuccessfulNonDryRun(projectId)` (excluding this run); none → `SpecDiff` with everything added and `firstRun=true`. Save to `diff_summary`. Output `{firstRun}`.
- `CalcVersion`: `lastVersion` = latest `sdk_versions.version` (empty on first run); strategy from config; save `sdk_version`, `change_type`. Output `{version, changed}` where `changed = changeType.isPresent()`.

## Tasks

### Task 1: Steps: LintSpec + DiffSpec + CalcVersion

- [ ] **Step 1: Write the failing test**

```java
@Test void lintFailOnError_withError_fails() { }
@Test void lintFailOnNever_continues() { }
@Test void lintRowsReplacedOnRetry() { }
@Test void diffBaselineIgnoresDryRuns() { }
@Test void firstRun_version010() { }
@Test void noChange_versionUnchanged_changedFalse() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK04*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK04*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): steps: lintspec + diffspec + calcversion"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
