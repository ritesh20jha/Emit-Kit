# VCS-03 · GitHub client: git data, PRs, tags

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Branches, single-commit tree writes, blob-sha listing, PRs, comments and tags on GitHub.

**Lane:** VCS · **Depends on:** [VCS-02](VCS-02-github-files.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/vcs-03`

**Spec:** v1 §7, §5.1 step 10

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/vcs/src/main/java/dev/emitkit/vcs/github/GitHubVcsClient.java`
- Test: `backend/vcs/src/test/java/dev/emitkit/vcs/github/GitHubGitDataTest.java`; extend `GitHubContractTest` stubs

## Interfaces

- **Produces:**
- `createBranch` → `POST /git/refs` (`refs/heads/{name}`); existing ref (422 "Reference already exists") → no-op if it points to `fromSha`, else `VcsConflictException`.
- `deleteBranch` → `DELETE /git/refs/heads/{name}`; 404/422 → no-op.
- `commitTree`: get branch head commit + its tree sha → `POST /git/trees` with `base_tree` and **inline `content`** entries (no separate blob calls) → `POST /git/commits` → `PATCH /git/refs/heads/{branch}`. Exactly 4 calls regardless of file count (up to 1000 files; above that, chunk trees of 1000 with chained `base_tree`).
- `listBlobShas(repo, ref, prefix)` → `GET /git/trees/{ref}?recursive=1`, filter `type=blob` and `path.startsWith(prefix)`; `truncated=true` → walk subtrees under prefix.
- `openPr`; existing open PR for the same head (422) → return that PR (idempotent).
- `createTag` → `POST /git/refs` `refs/tags/{tag}`; 422 "already exists" → `false`.

## Tasks

### Task 1: Git data over WireMock

- [ ] **Step 1: Write the failing test**

```java
@Test void commitTree_usesFourCallsFor50Files() {
  client.commitTree("acme/api", "emitkit/run-1", files(50), "msg");
  verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/api/git/trees")));
  verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/api/git/commits")));
  verify(1, patchRequestedFor(urlPathEqualTo("/repos/acme/api/git/refs/heads/emitkit/run-1")));
  verify(0, postRequestedFor(urlPathEqualTo("/repos/acme/api/git/blobs")));
}
@Test void listBlobShas_filtersByPrefix() { }
@Test void listBlobShas_truncatedTree_walksSubtrees() { }
@Test void openPr_existingOpenPr_returnsIt() { }
@Test void createTag_existing_returnsFalse() { }
@Test void deleteBranch_missing_isNoOp() { }
@Test void createBranch_existingAtSameSha_isNoOp() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*GitHubGitDataTest' --tests '*GitHubContractTest'`

- [ ] **Step 3: Implement** — use github-api's `GHTreeBuilder` (`add(path, content, false)`) and `GHRepository.createCommit()`; low-level calls through `GitHub.createRequest()` where the library lacks one.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*GitHubGitDataTest' --tests '*GitHubContractTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): github branches, trees, prs and tags"`

---

## Done when

- [ ] All tasks above are committed on `ticket/vcs-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
