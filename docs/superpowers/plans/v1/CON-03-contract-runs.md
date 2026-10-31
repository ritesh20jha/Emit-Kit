# CON-03 · Contract: runs, logs, files, artifact, versions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the REST contract for runs, logs, files, artifact, versions so frontend and backend work in parallel.

**Lane:** API · **Depends on:** [CON-01](CON-01-contract-me-orgs.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/con-03`

**Spec:** v1 §8 rows listed below; ov §5 (pagination, errors)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: controllers + DTO records under `backend/api-app/src/main/java/dev/emitkit/api/<feature>/` (see Interfaces)
- Modify: `frontend/openapi.json` (exported), `frontend/src/lib/api/generated/schema.d.ts` (generated) — both excluded from PR size

**Contract ticket rules (CON-01..03):** controllers are real `@RestController`s with final paths, request/response records, `@Valid` constraints and springdoc annotations; every method body is `throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented")` (501). Feature tickets replace bodies without changing the DTOs; changing a DTO later means regenerating `openapi.json` + client in that PR.

## Interfaces

- **Consumes:** `ProblemDetailsAdvice`, security chains (API-00).
- **Produces:**
**Endpoints:**
- `GET /api/v1/projects/{id}/runs?cursor&limit&status&trigger` → `Page<RunSummary>` · `POST /api/v1/projects/{id}/runs` body `TriggerRunRequest` → `201 RunDetail`
- `GET /api/v1/runs/{runId}` → `RunDetail` · `POST /api/v1/runs/{runId}/cancel` → `RunDetail` · `POST /api/v1/runs/{runId}/rerun` → `201 RunDetail` · `POST /api/v1/runs/{runId}/approval` body `ApprovalRequest` → `RunDetail`
- `GET /api/v1/runs/{runId}/logs?afterSeq` → `List<LogLine>` · `GET /api/v1/runs/{runId}/logs/stream` → `text/event-stream`: events `log` (data `LogLine`) and `done` (data `[DONE]`)
- `GET /api/v1/runs/{runId}/files` → `List<ManifestEntry>` · `GET /api/v1/runs/{runId}/files/{*path}?diff` → `FileView`
- `GET /api/v1/runs/{runId}/artifact` → `application/zip` · `GET /api/v1/runs/{runId}/lint` → `List<LintIssue>`
- `GET /api/v1/projects/{id}/versions` → `List<VersionResponse>`

**DTOs (records, camelCase JSON):**
```java
record TriggerRunRequest(boolean dryRun) {}
record RunSummary(String id, RunStatus status, RunTrigger trigger, boolean dryRun, @Nullable String sdkVersion, @Nullable Long durationMs, @Nullable Integer prNumber, @Nullable String prUrl, Instant createdAt) {}
record RunDetail(String id, String projectId, RunStatus status, RunTrigger trigger, boolean dryRun, @Nullable StepName currentStep,
    List<StepState> steps, @Nullable String failureCode, @Nullable String failureMessage, @Nullable String commitSha,
    @Nullable String sdkVersion, @Nullable ChangeType changeType, @Nullable SpecDiff diffSummary, LintCounts lint,
    @Nullable ApprovalView approval, @Nullable String branchName, @Nullable String prUrl, @Nullable String parentRunId,
    Instant createdAt, @Nullable Instant startedAt, @Nullable Instant finishedAt) {}
record StepState(StepName name, StepStatus status, @Nullable Long durationMs) { enum StepStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED } }
record LintCounts(int errors, int warnings, int infos) {}
record ApprovalView(@Nullable Decision decision, @Nullable String decidedBy, @Nullable String comment, @Nullable Instant decidedAt) {}
record ApprovalRequest(@NotNull Decision decision, @Size(max = 2000) String comment) {}
record LogLine(int seq, Instant ts, String level, @Nullable StepName step, String message) {}
record FileView(String path, FileStatus status, @Nullable String content, @Nullable String unifiedDiff) {}
record VersionResponse(String version, ChangeType changeType, String runId, String changelog, Instant createdAt) {}
```
`StepState` durations come from log timestamps of each step's first and last line (computed in RUN-01's mapper).

## Tasks

### Task 1: Contract + snapshot

- [ ] **Step 1: Write the failing test**

```java
@Test void streamDocumentedAsEventStream() { /* api-docs: paths./api/v1/runs/{runId}/logs/stream.get.responses.200.content has "text/event-stream" */ }
@Test void runsListFiltersBindEnums() { /* ?status=BOGUS → 400 */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*CON03ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 3: Implement** — controllers + DTOs; then `scripts/export-openapi.sh && (cd frontend && pnpm gen:api && pnpm tsc --noEmit)`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*CON03ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): contract: runs, logs, files, artifact, versions"`

---

## Done when

- [ ] All tasks above are committed on `ticket/con-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
