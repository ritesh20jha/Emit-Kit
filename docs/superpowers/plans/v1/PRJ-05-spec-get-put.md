# PRJ-05 · Spec get/put

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load the spec from GitHub and save it back with optimistic concurrency on the file sha.

**Lane:** API · **Depends on:** [PRJ-01](PRJ-01-create-existing.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/prj-05`

**Spec:** v1 §8 spec rows; README Review Focus #4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/projects/SpecService.java`, `SpecController.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/PRJ05SpecIT.java`

## Interfaces

- **Produces:**
- `GET` → `getFile(repo, specPath, defaultBranch)`; `format` from extension (`.json` → json, else yaml).
- `PUT` → size ≤ 2 MB (`SPEC_TOO_LARGE`), then `putFile(..., expectedSha = request.sha, message = request.message ?: "Update API spec (EmitKit)")`; `VcsConflictException` → `AppException(SPEC_CONFLICT, "Someone else changed this file", extra = {currentSha})` where `currentSha` is fetched after the conflict.
- `generate` is handled in RUN-05; here it is accepted and ignored.

## Tasks

### Task 1: Spec get/put

- [ ] **Step 1: Write the failing test**

```java
@Test void get_returnsContentShaFormat() { }
@Test void staleSha_returns409() {
  var s = getSpec(); otherUserSaves(s.sha());
  mvc.perform(put(spec).content(json(new SaveSpecRequest("x", s.sha(), null, false))))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPEC_CONFLICT")).andExpect(jsonPath("$.currentSha").isNotEmpty());
}
@Test void save_returnsCommitAndNewSha() { }
@Test void tooLarge_413() { }
@Test void viewerPut_403() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ05*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ05*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): spec get/put"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
