# DB-03 · Migration V3: runs, snapshots, logs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Flyway migration `V3__runs` (spec_snapshots, generation_runs, generation_run_logs) with JPA entities and Spring Data repositories.

**Lane:** CORE · **Depends on:** [DB-02](DB-02-projects-schema.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/db-03`

**Spec:** v1 §4 (tables, columns, enums, indexes)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/resources/db/migration/V3__runs.sql`
- Create: entities + repositories under `backend/domain/src/main/java/dev/emitkit/domain/` — `SpecSnapshot` (`apiModel` as JSONB `String`, parsed by callers), `GenerationRun` (enums `RunStatus {QUEUED, RUNNING, AWAITING_APPROVAL, SUCCESS, FAILED, CANCELLED}` with `isTerminal()`, `RunTrigger {MANUAL, WEBHOOK, RERUN}`, `StepName` — the 11 steps of v1 §5.1 in order, `ChangeType {MAJOR, MINOR, PATCH}`; `diffSummary` JSONB `String`), `GenerationRunLog` (BIGSERIAL id, not BaseEntity); repos `SpecSnapshotRepository.findByProjectIdAndContentHash`, `findByProjectIdOrderByCreatedAtDesc`; `GenerationRunRepository.findLastSuccessfulNonDryRun(projectId)`, `findByProjectId(projectId, cursor, limit)` keyset on `(created_at, id)` DESC; `GenerationRunLogRepository.findByRunIdAndSeqGreaterThanOrderBySeq`, `deleteByTsBefore(Instant)`
- Test: `backend/domain/src/test/java/dev/emitkit/domain/DB03RepositoriesIT.java` (extends `PostgresIT`)

## Interfaces

- **Produces:** the tables exactly as in v1 §4 (column names, `UNIQUE` constraints, defaults). Enums are stored as `varchar` with a `CHECK` constraint and mapped with `@Enumerated(STRING)`. IDs are `varchar(26)` assigned in Java with `Ids.newUlid()` in a `@PrePersist` on `BaseEntity` (DB-01). JSONB columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.
- Indexes: `generation_runs(project_id, created_at DESC)`, `generation_runs(status)`, `generation_run_logs(run_id, seq)`.
- `GenerationRun` also gets `step_outputs JSONB DEFAULT '{}'` (step name → saved output) — used by WRK-01 for idempotent resume; note it in the PR as an addition to v1 §4.

## Tasks

### Task 1: Migration V3__runs + entities

- [ ] **Step 1: Write the failing test**

```java
@Test void snapshotUniquePerProjectAndHash() { }
@Test void findLastSuccessfulNonDryRun_ignoresDryRunsAndFailures() {
  run(SUCCESS, false, t1); run(SUCCESS, true, t2); run(FAILED, false, t3);
  assertThat(runs.findLastSuccessfulNonDryRun(project.getId())).get().extracting(GenerationRun::getCreatedAt).isEqualTo(t1);
}
@Test void runList_isKeysetPaginatedNewestFirst() { /* 5 runs, limit 2 → 2, then cursor → next 2, then 1 */ }
@Test void logSeqUniquePerRun() { }
@Test void stepNamesInPipelineOrder() {
  assertThat(StepName.values()).extracting(Enum::name).containsExactly("FETCH_SPEC","PARSE_SPEC","LINT_SPEC","DIFF_SPEC",
      "CALC_VERSION","BREAKING_GATE","RUN_GENERATORS","SYNTAX_CHECK","PACKAGE_ARTIFACT","COMMIT_OUTPUT","FINALIZE");
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :domain:test --tests '*DB03RepositoriesIT'`

- [ ] **Step 3: Implement** — write `V3__runs.sql` from v1 §4; entities and repositories listed above. Cursor = base64 of `{createdAt}|{id}`, encoded/decoded by `RunCursor` in the same package.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :domain:test --tests '*DB03RepositoriesIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(domain): V3__runs spec_snapshots, generation_runs, generation_run_logs"`

---

## Done when

- [ ] All tasks above are committed on `ticket/db-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
