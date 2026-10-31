# HOOK-02 · Push trigger + merge tagging

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start a run when a push to the default branch touches the spec, and tag SDK versions when an EmitKit PR is merged.

**Lane:** API · **Depends on:** [HOOK-01](HOOK-01-webhook-core.md), [RUN-01](RUN-01-runs-create-list.md), [VCS-03](VCS-03-github-git-data.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/hook-02`

**Spec:** v1 §9 (push, pull_request rules)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/api-app/src/main/java/dev/emitkit/api/webhooks/WebhookDispatcher.java`
- Create: `backend/api-app/src/main/java/dev/emitkit/api/webhooks/PushHandler.java`, `MergeTagHandler.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/webhooks/HOOK02PushMergeIT.java`

## Interfaces

- **Produces:**
- push: `ref == "refs/heads/" + default_branch` **and** `spec_path` ∈ union of `commits[].added|modified|removed` → `RunService.create(project, WEBHOOK, false, null, null)` → 200 `{"status":"queued","runId"}`; else 200 `{"status":"ignored","reason":"branch"|"spec-not-changed"}`. Pushes by EmitKit's own merge commits still count (a merged SDK PR doesn't touch the spec, so no loop).
- pull_request: `action=closed`, `merged=true`, head ref matches `^emitkit/run-([0-9A-Z]{26})$` and the run belongs to this project → `createTag(repo, "sdk/v" + run.sdkVersion, merge_commit_sha)` and, if the run's config outputs include `MCP`, `mcp/v{version}`. Tag repo = the PR's repo (output repo in SEPARATE mode — candidate lookup in HOOK-01 must therefore also match projects by `output_repo_full_name`; add `findByRepoFullNameOrOutputRepoFullName`). `false` from `createTag` → log INFO, still 200.

## Tasks

### Task 1: Push trigger + merge tagging

- [ ] **Step 1: Write the failing test**

```java
@Test void pushToDefaultBranchTouchingSpec_createsOneWebhookRun() { }
@Test void pushToOtherBranch_ignored() { }
@Test void pushNotTouchingSpec_ignored() { }
@Test void mergedEmitkitPr_tagsSdkVersion() { }
@Test void mergedWithMcpOutput_alsoTagsMcp() { }
@Test void tagExists_200() { }
@Test void closedNotMerged_ignored() { }
@Test void separateMode_prOnOutputRepo_isMatched() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*HOOK02*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*HOOK02*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): push trigger + merge tagging"`

---

## Done when

- [ ] All tasks above are committed on `ticket/hook-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
