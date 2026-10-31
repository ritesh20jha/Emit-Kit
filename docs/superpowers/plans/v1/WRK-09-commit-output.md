# WRK-09 · Step: CommitOutput

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commit the output to a run branch in one tree and open the PR (skipped for dry runs).

**Lane:** WRK · **Depends on:** [WRK-08](WRK-08-package-artifact.md), [GEN-11](GEN-11-workflows.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-09`

**Spec:** v1 §5.1 step 10

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/CommitOutputStep.java`, `PrBody.java`, `BreakingComment.java`
- Create: `backend/worker-app/src/main/resources/templates/pr-body.mustache`, `breaking-comment.mustache`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK09CommitOutputTest.java`

## Interfaces

- **Produces:**
- `dry_run` → `Continue` with output `{skipped: true}` and log "Dry run: nothing committed".
- Branch `emitkit/run-{runId}` from the target branch head (`createBranch` is idempotent, VCS-03). Commit all non-`SKIPPED` / non-`UNCHANGED` files with `commitTree` (message `EmitKit: SDK v{version}`); nothing to commit → `Continue({noChanges: true})`, no PR.
- `openPr(title = "EmitKit: SDK v{version}", body = PrBody: changelog from `diff_summary` (Added / Changed / **Breaking**), lint summary counts, link to the run page `{emitkit.web.base-url}/runs/{runId}`)` — idempotent (VCS-03).
- Breaking changes → one comment from `breaking-comment.mustache`; skip if already posted (output saved right after the PR, comment flag saved after posting).
- Save `branch_name`, `pr_url`, `pr_number`. Register `branch_name` in the run so cancel cleanup (WRK-11) can delete it.

## Tasks

### Task 1: Step: CommitOutput

- [ ] **Step 1: Write the failing test**

```java
@Test void dryRun_skipsCommitAndPr() { }
@Test void append_commitsToSameRepo() { }
@Test void separate_commitsToOutputRepo() { }
@Test void branchAndTitle() { assertThat(vcs.prs()).singleElement().satisfies(pr -> { assertThat(pr.head()).isEqualTo("emitkit/run-" + runId); assertThat(pr.title()).isEqualTo("EmitKit: SDK v0.2.0"); }); }
@Test void skippedAndUnchangedNotCommitted() { }
@Test void breaking_postsOneComment() { }
@Test void conflict_failsVcsConflict() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK09*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK09*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: commitoutput"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-09`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
