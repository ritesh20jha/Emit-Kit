# WRK-02 · RunLogger

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Live run logs: publish each line to Redis for the SSE stream and flush batches to Postgres every 2 s and at step end.

**Lane:** WRK · **Depends on:** [WRK-01](WRK-01-pipeline-core.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-02`

**Spec:** ov §3 live logs

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/logs/RedisRunLogger.java`, `RunLoggerFactory.java`, `LogLineMessage.java`
- Modify: `backend/worker-app/src/main/java/dev/emitkit/worker/pipeline/RunContext.java` (interface `RunLogger` lives in `pipeline`)
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/logs/WRK02RunLoggerIT.java` (Redis + Postgres)

## Interfaces

- **Produces:**
```java
public interface RunLogger { void info(String m); void warn(String m); void error(String m); void step(StepName s); void flush(); void done(); }
public record LogLineMessage(int seq, Instant ts, String level, @Nullable StepName step, String message) {}  // Redis JSON
```
- Channel `run-logs:{runId}`; terminal message `{"type":"done"}` (RUN-03 relies on this exact shape).
- `seq` starts at `max(seq)+1` from Postgres (resume-safe), strictly increasing per run.
- Buffer flushed every 2 s by a scheduled task and on `step()` / `flush()` / `done()`; batch insert. Messages truncated at 8 KB per line.
- **Secrets never logged:** `RunLogger` masks any string registered via `mask(String)` (step code registers decrypted keys/tokens) → `****`.

## Tasks

### Task 1: RunLogger

- [ ] **Step 1: Write the failing test**

```java
@Test void seqStrictlyIncreasing_andResumesFromDb() { }
@Test void linesPublishedToRedis() { }
@Test void flushedOnStepChange() { }
@Test void flushedEveryTwoSeconds() { /* Awaitility */ }
@Test void donePublishesTerminalMessage() { assertThat(received()).last().isEqualTo("{\"type\":\"done\"}"); }
@Test void maskedValueNeverStoredOrPublished() { log.mask("sk-secret"); log.info("key=sk-secret"); assertThat(stored()).noneMatch(l -> l.contains("sk-secret")); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK02*'`

- [ ] **Step 3: Implement** — as listed; add `mask(String)` to the interface.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK02*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): runlogger"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
