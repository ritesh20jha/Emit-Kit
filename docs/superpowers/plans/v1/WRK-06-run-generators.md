# WRK-06 · Step: RunGenerators

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the selected generators in parallel and collect their files.

**Lane:** WRK · **Depends on:** [WRK-01](WRK-01-pipeline-core.md), [GEN-01](GEN-01-generator-spi.md), [AI-01](AI-01-docs-ai-assistant.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-06`

**Spec:** v1 §5.1 step 7, §6.1

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/RunGeneratorsStep.java`, `GeneratorRegistry.java`, `GeneratorSelection.java`, `WorkDir.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK06RunGeneratorsTest.java`

## Interfaces

- **Produces:**
- Selection: `SDK` → `TS_SDK|PY_SDK|JAVA_SDK` per `sdk_languages`; `CLI`, `MCP`, `DOCS` direct; `WORKFLOWS` whenever `SDK` is selected.
- Executor: `Executors.newVirtualThreadPerTaskExecutor()`; `checkCancelled()` polled every 1 s while waiting.
- AI: only if `DOCS` selected and `ai_api_key_enc` set: decrypt (context `project_configs:ai_api_key_enc:{configId}`), `log().mask(key)`, `ChatModelFactory.create(...)` → `GeminiDocsAiAssistant`; reference dropped at step end.
- Files written to `WorkDir.of(runId)` (`{emitkit.worker.work-dir}/{runId}/files/` + `files.json` list with kind) — the next steps read from there; output `{fileCount}`. On resume after a crash the work dir may be gone: steps 8–10 then re-run from this step (the runner treats a missing work dir as "RUN_GENERATORS output missing").

## Tasks

### Task 1: Step: RunGenerators

- [ ] **Step 1: Write the failing test**

```java
@Test void onlySelectedGeneratorsRun() { }
@Test void workflowsAddedWithSdk() { }
@Test void generatorThrows_failsNamingIt() { assertThat(reload().getFailureMessage()).contains("PY_SDK"); }
@Test void noAiKey_contextHasNoAi() { }
@Test void aiKeyMaskedInLogs() { }
@Test void cancelWhileGenerating_stops() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK06*'`

- [ ] **Step 3: Implement** — as listed; add the work-dir rule to `PipelineRunner` (skip check also requires `WorkDir.exists(runId)` for steps after RUN_GENERATORS).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK06*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: rungenerators"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-06`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
