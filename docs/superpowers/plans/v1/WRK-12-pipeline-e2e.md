# WRK-12 · Pipeline end-to-end test

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the whole pipeline end to end with real infrastructure containers, the real toolchain and a WireMock GitHub.

**Lane:** WRK · **Depends on:** [WRK-11](WRK-11-retry-cancel.md), [GEN-12](GEN-12-toolchain-compile-tests.md), [HOOK-02](HOOK-02-push-merge.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-12`

**Spec:** v1 §11 (run duration), §12 parity

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/test/java/dev/emitkit/worker/e2e/WRK12PipelineE2EIT.java`, `backend/worker-app/src/test/java/dev/emitkit/worker/e2e/GitHubMock.java` (stateful WireMock extension: repos, refs, trees, PRs)
- Create: `.github/workflows/e2e-backend.yml` (nightly + on `backend/**` changes to `main`; gVisor installed on the runner)

## Interfaces

- **Produces:**
- Containers: Postgres, RabbitMQ, Redis, Vault (Transit init), RustFS, toolchain image; the worker runs in-process (`@SpringBootTest`) with `emitkit.sandbox.runtime=runsc` in CI.
- GitHub is a stateful WireMock (`GitHubMock`) so commits made by CommitOutput are visible to `listBlobShas` on the next run.

## Tasks

### Task 1: Pipeline end-to-end test

- [ ] **Step 1: Write the failing test**

```java
@Test void petstoreAllOutputs_success_prOpened_artifactStored() { }
@Test void secondRunWithNoSpecChange_versionUnchanged_noNewPr() { }
@Test void breakingChange_withRequireApproval_parksThenCompletesAfterApproval() { }
@Test void big200Ops_finishesUnder3Minutes() { assertTimeoutPreemptively(Duration.ofMinutes(3), () -> runToEnd("big-200-ops")); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK12*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK12*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): pipeline end-to-end test"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-12`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
