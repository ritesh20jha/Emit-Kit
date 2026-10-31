# RUN-02 · Cancel, rerun, approval endpoints

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cancel active runs, rerun with the same config, and approve or reject runs waiting at the breaking-change gate.

**Lane:** API · **Depends on:** [RUN-01](RUN-01-runs-create-list.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/run-02`

**Spec:** v1 §5.1 step 6, §8 (cancel, rerun, approval); ov §3 cancellation

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/api-app/src/main/java/dev/emitkit/api/runs/RunService.java`, `RunController.java`
- Create: `backend/api-app/src/main/java/dev/emitkit/api/runs/ControlPublisher.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/runs/RUN02RunActionsIT.java`

## Interfaces

- **Produces:**
- `cancel`: allowed for `QUEUED | RUNNING | AWAITING_APPROVAL` else `AppException(CONFLICT)`. Sets `cancel_requested=true`; publishes `ControlMessage("CANCEL", runId)` to `emitkit.control`. `QUEUED` and `AWAITING_APPROVAL` runs are set to `CANCELLED` immediately by the API (no worker is holding them); `RUNNING` is finished by the worker.
- `rerun`: new run, `trigger=RERUN`, same `config_id` as the source, `parent_run_id` = source; source may be in any terminal status.
- `approval` (`RUN_APPROVE`): run must be `AWAITING_APPROVAL` (else 409); `APPROVED` → set approval decision, status `QUEUED`, publish `GenerationJob(runId, 0)` after commit; `REJECTED` → status `CANCELLED`, `failure_message` = "Rejected: {comment}".

## Tasks

### Task 1: Cancel, rerun, approval endpoints

- [ ] **Step 1: Write the failing test**

```java
@Test void cancelSuccessRun_409() { }
@Test void cancelRunning_setsFlag_andPublishesControl() { }
@Test void cancelQueued_isCancelledImmediately() { }
@Test void rerun_copiesConfigVersion_setsParent() { }
@Test void approve_reEnqueues() { }
@Test void reject_cancels_withComment() { }
@Test void editorApproving_403() { }
@Test void approveTwice_409() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RUN02*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RUN02*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): cancel, rerun, approval endpoints"`

---

## Done when

- [ ] All tasks above are committed on `ticket/run-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
