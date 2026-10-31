# WRK-11 · Retry, DLQ, cancellation, idempotency

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the listener to the pipeline with retry queues, the DLQ, cancel handling and duplicate-delivery safety.

**Lane:** WRK · **Depends on:** [WRK-10](WRK-10-finalize.md), [RUN-02](RUN-02-cancel-rerun-approve.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-11`

**Spec:** ov §3 messaging; v1 §5.1 cancellation and retry; README Review Focus #1 and #5

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/worker-app/src/main/java/dev/emitkit/worker/messaging/GenerationJobListener.java`
- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/messaging/ControlListener.java`, `RetryRouter.java`, `CancelCleanup.java`, `RunLock.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/messaging/WRK11RetryCancelIT.java` (RabbitMQ + Postgres + Redis; fake sandbox; `InMemoryVcsClient`)

## Interfaces

- **Produces:**
- Listener: acquire `RunLock` (Postgres advisory lock `pg_try_advisory_lock(hashtext(runId))`); not acquired → ack and drop (another worker has it). Then `PipelineRunner.run(runId)`; ack.
- `TransientException`: `attempt+1`; if `attempt+1 > MAX_ATTEMPTS` → publish to `generation.dlq`, mark `FAILED` (`failure_code` = the exception's code, message "Gave up after 3 attempts: …"); else publish `GenerationJob(runId, attempt+1)` to `Queues.retryQueueFor(attempt+1)`, update `generation_runs.attempt`, log WARN "Retrying in 10s". Ack the original either way.
- `ControlListener`: `CANCEL` → `CancellationRegistry.add(runId)` + cancel sandbox token for that run.
- `CancelledException` → `CancelCleanup`: kill sandbox (token), `deleteBranch` if `branch_name` set, status `CANCELLED`, `finished_at`, `log().done()`.
- Unexpected `Exception` → `FAILED`, `INTERNAL`, message without stack trace; stack trace in worker log with `runId` MDC.

## Tasks

### Task 1: Retry, DLQ, cancellation, idempotency

- [ ] **Step 1: Write the failing test**

```java
@Test void redeliveredRunMessage_opensOnePr() { publish(job); publish(job); awaitTerminal(); assertThat(vcs.calls("openPr")).hasSize(1); assertThat(sdkVersions.count()).isEqualTo(1); }
@Test void transientFailure_retriesWithNextAttempt() { }
@Test void threeTransientFailures_goToDlq_andFail() { }
@Test void cancelDuringSandbox_killsContainerAndCleansBranch() {
  sandbox.blockUntilCancelled(); publish(job); awaitStep(SYNTAX_CHECK);
  markCancelRequested(runId); rabbit.convertAndSend(CONTROL_EXCHANGE, "", new ControlMessage("CANCEL", runId));  // what RUN-02 does
  await().atMost(10, SECONDS).until(() -> reload().getStatus() == CANCELLED);
  assertThat(sandbox.killed()).isTrue(); assertThat(vcs.branches()).doesNotContain("emitkit/run-" + runId);
}
@Test void concurrentDeliveries_onlyOneWorkerRuns() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK11*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK11*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): retry, dlq, cancellation, idempotency"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-11`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
