# RUN-01 · Create/list/get runs + enqueue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create runs from the latest config and publish the job only after the transaction commits.

**Lane:** API · **Depends on:** [AUTH-03](AUTH-03-org-access.md), [DB-04](DB-04-run-results-schema.md), [WRK-00](WRK-00-worker-skeleton.md), [CON-03](CON-03-contract-runs.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/run-01`

**Spec:** v1 §8 runs rows; ov §3 messaging

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/runs/RunService.java`, `RunController.java`, `RunMapper.java`, `JobPublisher.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/runs/RUN01RunsIT.java` (Postgres + RabbitMQ Testcontainers)

## Interfaces

- **Produces:**
```java
public GenerationRun create(String projectId, RunTrigger trigger, boolean dryRun, @Nullable String userId, @Nullable String parentRunId);
```
- Status `QUEUED`, `config_id` = latest config (or the parent's for RERUN, see RUN-02), `attempt=0`.
- `JobPublisher.publishAfterCommit(runId)` registers a `TransactionSynchronization.afterCommit` that sends `GenerationJob(runId, 0)` to `emitkit.jobs`/`generation`. If publishing throws, mark the run `FAILED` with `failure_code=INTERNAL`, message "Could not queue run" and rethrow as `AppException(INTERNAL)` (RUN-05 relies on this).
- `RunMapper` builds `RunDetail` incl. `steps` (from `current_step` + status + per-step first/last log ts), lint counts, approval.

## Tasks

### Task 1: Create/list/get runs + enqueue

- [ ] **Step 1: Write the failing test**

```java
@Test void create_publishesJobAfterCommit() { var r = runs.create(p, MANUAL, false, u, null); assertThat(rabbit.receiveAndConvert("generation.jobs", 2000)).isEqualTo(new GenerationJob(r.getId(), 0)); }
@Test void rollback_publishesNothing() { /* create inside a tx that throws → queue empty after 1 s */ }
@Test void list_filtersByStatusAndTrigger_cursorPaginates() { }
@Test void get_includesSteps_andLintCounts() { }
@Test void viewerTrigger_403() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RUN01*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RUN01*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): create/list/get runs + enqueue"`

---

## Done when

- [ ] All tasks above are committed on `ticket/run-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
