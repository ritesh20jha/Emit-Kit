# RUN-05 · Save spec with generate=true

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commit the spec and start a run in one request, reporting a partial failure without losing the commit.

**Lane:** API · **Depends on:** [PRJ-05](PRJ-05-spec-get-put.md), [RUN-01](RUN-01-runs-create-list.md) · **Size budget:** ~250 changed lines (hard limit 1000) · **Branch:** `ticket/run-05`

**Spec:** v1 §8 note on `generate=true`

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/api-app/src/main/java/dev/emitkit/api/projects/SpecController.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/RUN05SaveAndGenerateIT.java`

## Interfaces

- **Produces:**
- `generate=true` requires both `SPEC_EDIT` and `RUN_TRIGGER`.
- Commit first. Then `RunService.create(..., MANUAL, false, ...)`. Success → `200 {commitSha, newSha, runId}`. Run creation/enqueue fails → `202 {commitSha, newSha, runId: null, runError: {code, detail}}`.

## Tasks

### Task 1: Save spec with generate=true

- [ ] **Step 1: Write the failing test**

```java
@Test void bothSucceed_200WithRunId() { }
@Test void enqueueFails_202WithRunError_commitKept() { publisher.failNext(); var r = save(true); assertThat(r.status()).isEqualTo(202); assertThat(r.body().runError().code()).isEqualTo("INTERNAL"); assertThat(vcs.file(spec).content()).isEqualTo(newContent); }
@Test void generateFalse_noRun() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RUN05*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RUN05*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): save spec with generate=true"`

---

## Done when

- [ ] All tasks above are committed on `ticket/run-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
