# RUN-03 · Logs + SSE stream

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve stored logs and a live SSE stream that replays history then follows Redis without gaps or duplicates.

**Lane:** API · **Depends on:** [RUN-01](RUN-01-runs-create-list.md), [WRK-02](WRK-02-run-logger.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/run-03`

**Spec:** ov §3 live logs; v1 §8 logs rows

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/runs/LogStreamService.java`, `LogController.java`, `RedisLogSubscriber.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/runs/RUN03LogStreamIT.java` (Postgres + Redis)

## Interfaces

- **Produces:**
- `GET /logs?afterSeq=` returns stored lines with `seq > afterSeq` (max 5000).
- Stream algorithm (no gap, no dup): (1) subscribe to `run-logs:{runId}` and **buffer** incoming; (2) send stored lines with `seq > Last-Event-ID` (header, default 0); (3) flush buffered lines with `seq > lastSent`; (4) forward live lines skipping `seq <= lastSent`; SSE `id` = seq. On a message of type `done` (published by `RunLogger.done()`), send event `done` data `[DONE]` and complete. If the run is already terminal at connect time, replay then send `done` immediately.
- `SseEmitter` timeout 30 min; heartbeat comment every 15 s; unsubscribe on completion/timeout/error.
- Redis message JSON = `{seq, ts, level, step, message}` or `{"type":"done"}` (format owned by WRK-02).

## Tasks

### Task 1: Logs + SSE stream

- [ ] **Step 1: Write the failing test**

```java
@Test void replaysStoredThenLive_inOrder_noDuplicates() { storeLogs(1..5); publishLive(4..8); assertThat(streamSeqs()).containsExactly(1,2,3,4,5,6,7,8); }
@Test void lastEventId_resumesAfterIt() { }
@Test void closesOnDone() { }
@Test void terminalRun_replaysAndCloses() { }
@Test void clientDisconnect_unsubscribes() { assertThat(redisSubscriberCount("run-logs:r1")).isZero(); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RUN03*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RUN03*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): logs + sse stream"`

---

## Done when

- [ ] All tasks above are committed on `ticket/run-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
