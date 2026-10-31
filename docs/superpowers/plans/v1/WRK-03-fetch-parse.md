# WRK-03 · Steps: FetchSpec + ParseSpec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fetch the spec at the default branch head and parse it into a stored snapshot.

**Lane:** WRK · **Depends on:** [WRK-01](WRK-01-pipeline-core.md), [VCS-05](VCS-05-rate-limits.md), [SPEC-03](SPEC-03-parser-schemas.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-03`

**Spec:** v1 §5.1 steps 1–2; README Review Focus #3

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/FetchSpecStep.java`, `ParseSpecStep.java`, `FetchOutput.java`
- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/vcs/WorkerVcsConfig.java` (provides `VcsClient` per run via `forProject(createdBy, BACKGROUND)`)
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK03FetchParseTest.java` (`InMemoryVcsClient`)

## Interfaces

- **Produces:**
- `FetchSpec`: validate `repo_full_name` against `^[\w.-]+/[\w.-]+$` (else `SPEC_NOT_FOUND`); resolve head sha of `default_branch`; `getFile` at that sha; > 2 MB (UTF-8 bytes) → `SPEC_TOO_LARGE`; `VcsNotFoundException` → `SPEC_NOT_FOUND`; `VcsAuthException` → `Fail(VCS_AUTH, "The project creator's GitHub access no longer works. An admin must re-link the project.")` — **not** retried. Output `FetchOutput(commitSha, content)`; also sets `generation_runs.commit_sha`.
  Content is kept in the output only until ParseSpec stores the snapshot (ParseSpec replaces the FetchSpec output with `{commitSha}` to keep `step_outputs` small).
- `ParseSpec`: `SpecParser.parse`; errors → `SPEC_INVALID` with the first 5 errors; upsert `spec_snapshots` by `(project_id, content_hash)` with `api_model = ApiModelJson.toJson(model)`; set `spec_snapshot_id`; output `{snapshotId}`.

## Tasks

### Task 1: Steps: FetchSpec + ParseSpec

- [ ] **Step 1: Write the failing test**

```java
@Test void tooLarge_failsSpecTooLarge() { }
@Test void badRepoName_failsSpecNotFound() { }
@Test void missingFile_failsSpecNotFound() { }
@Test void revokedToken_failsWithVcsAuth_noRetry() { vcs.failNext(new VcsAuthException("bad credentials")); runner.run(runId); assertThat(reload().getFailureCode()).isEqualTo("VCS_AUTH"); assertThat(rabbit.retryQueuesDepth()).isZero(); }
@Test void serverError_isTransient() { }
@Test void snapshotReusedWhenHashMatches() { }
@Test void invalidSpec_failsSpecInvalid() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK03*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK03*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): steps: fetchspec + parsespec"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
