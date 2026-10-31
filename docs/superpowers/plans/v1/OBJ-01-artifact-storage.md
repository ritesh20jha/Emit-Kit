# OBJ-01 · Artifact storage client (RustFS / S3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A small storage interface over the S3 API, tested against RustFS, used for run artifacts by both apps.

**Lane:** PLAT · **Depends on:** [T01](T01-backend-skeleton.md), [T03](T03-local-stack.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/obj-01`

**Spec:** v1 §5.1 step 9, §4 retention, §8 artifact download

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/java/dev/emitkit/domain/storage/ArtifactStorage.java`
- Create: `backend/domain/src/main/java/dev/emitkit/domain/storage/S3ArtifactStorage.java`
- Create: `backend/domain/src/main/java/dev/emitkit/domain/storage/StorageProperties.java` (`@ConfigurationProperties("emitkit.storage")`: `endpoint`, `bucket`, `accessKey`, `secretKey`, `region` default `us-east-1`)
- Create: `backend/domain/src/main/java/dev/emitkit/domain/storage/StorageException.java` (extends `TransientException` from CORE-01 — if CORE-01 isn't merged yet, extend `RuntimeException` and switch in CORE-01's PR)
- Test: `backend/domain/src/test/java/dev/emitkit/domain/storage/S3ArtifactStorageIT.java`, `src/test/java/.../RustFsContainer.java`

## Interfaces

- **Produces:**
```java
public interface ArtifactStorage {
  void put(String key, Path file);                 // overwrites
  InputStream get(String key);                     // throws NoSuchArtifactException if missing
  void delete(String key);                         // missing key = no-op
  List<String> listOlderThan(String prefix, Instant cutoff);
}
```
  Key format used by the worker: `artifacts/{orgId}/{runId}.zip`.
- **Consumes:** AWS SDK v2 `S3Client` (path-style access on, endpoint override).

## Tasks

### Task 1: Storage round-trip

- [ ] **Step 1: Write `RustFsContainer`** — `GenericContainer` with the same pinned RustFS image as T03, exposed port 9000, wait for HTTP 200 on the health path; creates the bucket in `@BeforeAll` via `S3Client.createBucket`.
- [ ] **Step 2: Write the failing tests**

```java
@Test void put_thenGet_returnsSameBytes()          // 1 MB random file
@Test void get_missing_throwsNoSuchArtifact()
@Test void delete_missing_isNoOp()
@Test void listOlderThan_returnsOnlyOldKeys()     // put a/1, a/2; cutoff = now + 1s → both; cutoff = before first put → none
@Test void put_overwritesExistingKey()
```
- [ ] **Step 3: Run — expect FAIL.** Run: `./gradlew :domain:test --tests '*S3ArtifactStorageIT'`
- [ ] **Step 4: Implement `S3ArtifactStorage`** (`listObjectsV2` paginated, compare `lastModified`; map `NoSuchKeyException` → `NoSuchArtifactException`; SDK client errors with 5xx → `StorageException`).
- [ ] **Step 5: Run — expect PASS.**
- [ ] **Step 6: Commit** — `git commit -m "feat(storage): S3 artifact storage for RustFS"`

---

## Done when

- [ ] All tasks above are committed on `ticket/obj-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
