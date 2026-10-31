# WRK-10 · Step: Finalize

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mark the run successful, record the new version, and close the log stream.

**Lane:** WRK · **Depends on:** [WRK-09](WRK-09-commit-output.md) · **Size budget:** ~250 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-10`

**Spec:** v1 §5.1 step 11

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/FinalizeStep.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK10FinalizeTest.java`

## Interfaces

- **Produces:**
- Status `SUCCESS`, `finished_at`. Insert `sdk_versions(version, change_type, changelog = PR body changelog section)` only if `CalcVersion.changed` and not dry run; `existsByProjectIdAndVersion` → skip (idempotent).
- `log().done()`; delete the work dir.

## Tasks

### Task 1: Step: Finalize

- [ ] **Step 1: Write the failing test**

```java
@Test void changedVersion_insertsSdkVersion() { }
@Test void unchanged_noRow() { }
@Test void dryRun_noRow() { }
@Test void doneCalled_workDirDeleted() { }
@Test void rerunAfterFinalize_noDuplicateRow() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK10*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK10*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: finalize"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-10`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
