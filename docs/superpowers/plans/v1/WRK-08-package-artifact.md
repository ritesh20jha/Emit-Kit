# WRK-08 · Step: PackageArtifact

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zip the output, upload it, and build the manifest by comparing each file with the target branch.

**Lane:** WRK · **Depends on:** [WRK-01](WRK-01-pipeline-core.md), [OBJ-01](OBJ-01-artifact-storage.md), [VCS-03](VCS-03-github-git-data.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-08`

**Spec:** v1 §5.1 step 9

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/steps/PackageArtifactStep.java`, `ManifestBuilder.java`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/steps/WRK08PackageArtifactTest.java`

## Interfaces

- **Produces:**
- Target repo/branch: APPEND → project repo + `default_branch`; SEPARATE → `output_repo_full_name` + its default branch.
- `listBlobShas(repo, branch, "")` once (whole tree; filter to generated paths); status per file: not present → `ADDED`; same `GitBlob.sha(content)` → `UNCHANGED`; else `MODIFIED`; `CUSTOM_STUB` file whose path already exists → `SKIPPED`.
- Zip contains every non-`SKIPPED` file (deterministic order, fixed timestamps); upload to `artifacts/{orgId}/{runId}.zip`; upsert `run_artifacts` (manifest with `sha256` of content, size).

## Tasks

### Task 1: Step: PackageArtifact

- [ ] **Step 1: Write the failing test**

```java
@Test void manifestStatuses() { /* existing same → UNCHANGED, existing different → MODIFIED, new → ADDED */ }
@Test void existingCustomStub_isSkipped_andNotZipped() { }
@Test void zipIsDeterministic() { }
@Test void storageDown_isTransient() { }
@Test void separateMode_comparesWithOutputRepo() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*WRK08*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*WRK08*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): step: packageartifact"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-08`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
