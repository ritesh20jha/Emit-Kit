# RUN-04 · Files preview, artifact download, lint results, versions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a run's generated files and diffs, stream its zip, and list lint results and versions.

**Lane:** API · **Depends on:** [RUN-01](RUN-01-runs-create-list.md), [OBJ-01](OBJ-01-artifact-storage.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/run-04`

**Spec:** v1 §8 (files, artifact, lint, versions)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/runs/RunFilesService.java`, `RunFilesController.java`, `VersionsController.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/runs/RUN04FilesIT.java`

## Interfaces

- **Produces:**
- `files` → artifact manifest. `files/{*path}`: path must be in the manifest (else 404; reject `..`, absolute paths, backslashes with 400 `VALIDATION_FAILED` before lookup); content read from the zip in RustFS (stream through `ZipInputStream` to that entry); `?diff=true` → unified diff (java-diff-utils, 3 lines context) against the target branch's file via `getFile` (ADDED → diff from empty).
- `artifact` → `StreamingResponseBody` copying `ArtifactStorage.get(key)`; headers `Content-Type: application/zip`, `Content-Disposition: attachment; filename="emitkit-{runId}.zip"`; missing artifact (expired/never) → 404 `NOT_FOUND` with detail "Artifact expired or not created".
- `lint` → rows for the run; `versions` → `sdk_versions` newest first.

## Tasks

### Task 1: Files preview, artifact download, lint results, versions

- [ ] **Step 1: Write the failing test**

```java
@Test void manifestListed() { }
@Test void modifiedFile_diffAgainstBase() { assertThat(fileView("sdk/typescript/src/client.ts", true).unifiedDiff()).startsWith("--- a/").contains("+"); }
@Test void pathTraversal_400() { }
@Test void artifactStreamsZip() { }
@Test void expiredArtifact_404() { }
@Test void versionsNewestFirst() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RUN04*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RUN04*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): files preview, artifact download, lint results, versions"`

---

## Done when

- [ ] All tasks above are committed on `ticket/run-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
