# DB-04 · Migration V4: run results

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Flyway migration `V4__run_results` (run_artifacts, lint_results, run_approvals, sdk_versions) with JPA entities and Spring Data repositories.

**Lane:** CORE · **Depends on:** [DB-03](DB-03-runs-schema.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/db-04`

**Spec:** v1 §4 (tables, columns, enums, indexes)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/resources/db/migration/V4__run_results.sql`
- Create: entities + repositories under `backend/domain/src/main/java/dev/emitkit/domain/` — `RunArtifact` (`manifest` JSONB as `List<ManifestEntry>`; `record ManifestEntry(String path, String sha256, long size, FileStatus status)`, `FileStatus {ADDED, MODIFIED, UNCHANGED, SKIPPED}`), `LintResultRow` (enum `Severity {ERROR, WARNING, INFO}`), `RunApproval` (enum `Decision {APPROVED, REJECTED}`), `SdkVersion`; repos `RunArtifactRepository.findByRunId`, `findByCreatedAtBefore`; `LintResultRepository.findByRunId`, `countBySeverity(runId)`; `RunApprovalRepository.findByRunId`; `SdkVersionRepository.findByProjectIdOrderByCreatedAtDesc`, `existsByProjectIdAndVersion`
- Test: `backend/domain/src/test/java/dev/emitkit/domain/DB04RepositoriesIT.java` (extends `PostgresIT`)

## Interfaces

- **Produces:** the tables exactly as in v1 §4 (column names, `UNIQUE` constraints, defaults). Enums are stored as `varchar` with a `CHECK` constraint and mapped with `@Enumerated(STRING)`. IDs are `varchar(26)` assigned in Java with `Ids.newUlid()` in a `@PrePersist` on `BaseEntity` (DB-01). JSONB columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.
- Index `sdk_versions(project_id, created_at DESC)`.

## Tasks

### Task 1: Migration V4__run_results + entities

- [ ] **Step 1: Write the failing test**

```java
@Test void oneArtifactPerRun() { }
@Test void manifestRoundTrips() { /* 2 entries with ADDED + SKIPPED → equal after reload */ }
@Test void oneApprovalPerRun() { }
@Test void sdkVersionUniquePerProject() { }
@Test void lintCountsBySeverity() { /* 2 ERROR, 1 INFO → {ERROR=2, INFO=1} */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :domain:test --tests '*DB04RepositoriesIT'`

- [ ] **Step 3: Implement** — write `V4__run_results.sql` from v1 §4; entities and repositories listed above. `countBySeverity` is a JPQL group-by returning `List<SeverityCount>` (record).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :domain:test --tests '*DB04RepositoriesIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(domain): V4__run_results run_artifacts, lint_results, run_approvals, sdk_versions"`

---

## Done when

- [ ] All tasks above are committed on `ticket/db-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
