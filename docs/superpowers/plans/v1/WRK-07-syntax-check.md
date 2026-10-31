# WRK-07 · Step: SyntaxCheck

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send generated files through the gVisor sandbox for compile checks and formatting, and keep the formatted output.

**Lane:** WRK · **Depends on:** [WRK-01](WRK-01-pipeline-core.md), [SBX-03](SBX-03-docker-sandbox-runner.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-07`

**Spec:** v1 §5.1 step 8, §5.6

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/SyntaxCheckStep.java`, `CheckReport.java`, `Tars.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK07SyntaxCheckTest.java` (fake `SandboxRunner`)

## Interfaces

- **Produces:**
- Build `in.tar` from the work dir (layout from SBX-02, with `languages.txt`), call `SandboxRunner.run(new SandboxRequest(toolchainImage, inTar, 180s), token)` where `token` is cancelled by the control listener.
- `report.json` → `CheckReport(List<ToolResult(tool, exitCode, output)>)`. Any non-zero → `Fail(CHECK_FAILED, "{tool} failed")` and the tool output logged (first 200 lines).
- `timedOut` → `Fail(SANDBOX_TIMEOUT, ...)`. `SandboxInfraException` (Docker down) → transient.
- Success: replace work-dir files with the formatted ones from `out.tar`; only paths that existed before are accepted (ignore anything new).

## Tasks

### Task 1: Step: SyntaxCheck

- [ ] **Step 1: Write the failing test**

```java
@Test void formattedFilesReplaceOriginals() { }
@Test void failingReport_failsCheckFailed_logsOutput() { }
@Test void timeout_failsSandboxTimeout() { }
@Test void infraError_isTransient() { }
@Test void unexpectedNewFileInOutput_ignored() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK07*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK07*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: syntaxcheck"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-07`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
