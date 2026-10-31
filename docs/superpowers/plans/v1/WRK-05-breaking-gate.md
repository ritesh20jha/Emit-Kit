# WRK-05 · Step: BreakingGate

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the project's breaking-change policy: continue, fail, or park the run for approval.

**Lane:** WRK · **Depends on:** [WRK-04](WRK-04-lint-diff-version.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-05`

**Spec:** v1 §5.1 step 6

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/BreakingGateStep.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK05BreakingGateTest.java`

## Interfaces

- **Produces:**
- No breaking changes, or policy `ALLOW` → `Continue`.
- `BLOCK` → `Fail(BREAKING_BLOCKED, "{n} breaking changes; policy is BLOCK")`.
- `REQUIRE_APPROVAL`: if a `run_approvals` row exists with `APPROVED` → `Continue` (this is the resume after RUN-02 re-enqueued). Else create the row (if missing), set status `AWAITING_APPROVAL`, log the breaking changes, `Park`.

## Tasks

### Task 1: Step: BreakingGate

- [ ] **Step 1: Write the failing test**

```java
@Test void allow_continues() { }
@Test void block_fails() { }
@Test void requireApproval_parksAndCreatesApproval() { }
@Test void approvedResume_continuesToRunGenerators() { /* approval APPROVED, step outputs up to CALC_VERSION → runner continues at RUN_GENERATORS */ }
@Test void redeliveredWhileAwaiting_createsNoSecondApproval() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK05*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK05*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: breakinggate"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
