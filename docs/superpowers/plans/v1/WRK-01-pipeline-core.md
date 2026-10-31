# WRK-01 · Pipeline core

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The step interface, run context and runner that executes the 11 steps in order, saves each step's output, and resumes where a run stopped.

**Lane:** WRK · **Depends on:** [WRK-00](WRK-00-worker-skeleton.md), [DB-04](DB-04-run-results-schema.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-01`

**Spec:** v1 §5.1 (step interface, idempotency, resume)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/pipeline/PipelineStep.java`, `StepResult.java`, `RunContext.java`, `PipelineRunner.java`, `StepOutputs.java`, `CancelledException.java`, `CancellationRegistry.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/pipeline/WRK01PipelineRunnerTest.java` (fake steps, Postgres Testcontainers)

## Interfaces

- **Produces:**
```java
public interface PipelineStep { StepName name(); StepResult execute(RunContext ctx) throws Exception; }
public sealed interface StepResult {
  record Continue(@Nullable Object output) implements StepResult {}      // output saved under step name (JSON)
  record Park() implements StepResult {}                                 // stop; step already set status (e.g. AWAITING_APPROVAL)
  record Fail(ErrorCode code, String message) implements StepResult {}
}
public final class RunContext {
  GenerationRun run(); Project project(); ProjectConfig config(); VcsClient vcs(); RunLogger log();
  void checkCancelled();                                   // throws CancelledException if flag set (memory or DB)
  <T> Optional<T> output(StepName step, Class<T> type);    // earlier step outputs
}
public class PipelineRunner { public void run(String runId); }
```
- `run()`: load run; if terminal → return (idempotent redelivery). Set `RUNNING`, `started_at` (first time only). For each step in `StepName` order: if `step_outputs` has the step → skip (log DEBUG); else `checkCancelled()`, `log().step(name)`, execute, persist `{current_step, step_outputs[name]}` in its own transaction. `Fail` → status `FAILED`, `failure_code`, `failure_message`, `finished_at`. `Park` → return. After `FINALIZE` → nothing (Finalize sets SUCCESS).
- `AppException` (non-transient) thrown by a step → treated as `Fail(code, message)`. `TransientException` → propagates (WRK-11 routes it to retry). `CancelledException` → propagates (WRK-11 cleans up).
- `CancellationRegistry`: in-memory `Set<String>` of runIds, filled by the control listener (WRK-11); `checkCancelled` also re-reads `cancel_requested` from the DB at most every 5 s.
- Steps are Spring beans; the runner gets `List<PipelineStep>` and checks at startup that exactly one bean exists per `StepName`.

## Tasks

### Task 1: Pipeline core

- [ ] **Step 1: Write the failing test**

```java
@Test void runsStepsInOrder() { runner.run(runId); assertThat(recorder.order()).containsExactly(StepName.values()); }
@Test void persistedStepIsSkippedOnResume() { saveOutputs(runId, FETCH_SPEC, PARSE_SPEC, LINT_SPEC); runner.run(runId); assertThat(recorder.order()).startsWith(DIFF_SPEC); }
@Test void failSetsFailureCode() { steps.fail(LINT_SPEC, LINT_FAILED, "2 errors"); runner.run(runId); assertThat(reload().getStatus()).isEqualTo(FAILED); assertThat(reload().getFailureCode()).isEqualTo("LINT_FAILED"); assertThat(recorder.order()).doesNotContain(DIFF_SPEC); }
@Test void appExceptionBecomesFail() { }
@Test void transientExceptionPropagates_runStaysRunning() { }
@Test void parkStops() { }
@Test void terminalRun_isNoOp() { }
@Test void missingStepBean_failsStartup() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK01*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK01*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): pipeline core"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
