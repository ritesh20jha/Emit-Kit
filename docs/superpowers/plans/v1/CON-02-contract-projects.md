# CON-02 · Contract: projects, config, spec, lint

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the REST contract for projects, config, spec, lint so frontend and backend work in parallel.

**Lane:** API · **Depends on:** [CON-01](CON-01-contract-me-orgs.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/con-02`

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
- `GET /api/v1/orgs/{orgId}/projects?cursor&limit&q` → `Page<ProjectSummary>` · `POST /api/v1/orgs/{orgId}/projects` body `CreateProjectRequest` → `201 ProjectDetail`
- `GET /api/v1/orgs/{orgId}/vcs-repos?q` → `List<RepoOption>` · `GET /api/v1/orgs/{orgId}/vcs-repos/{owner}/{repo}/spec-check?path&branch` → `SpecCheckResponse`
- `GET /api/v1/projects/{id}` → `ProjectDetail` · `DELETE /api/v1/projects/{id}` → `204` · `POST /api/v1/projects/{id}/webhook` → `WebhookStatus`
- `GET|PUT /api/v1/projects/{id}/config` → `ConfigResponse` (PUT body `ConfigRequest`)
- `GET /api/v1/projects/{id}/spec` → `SpecResponse` · `PUT /api/v1/projects/{id}/spec` body `SaveSpecRequest` → `200|202 SaveSpecResponse`
- `GET /api/v1/projects/{id}/spec/snapshots` → `List<SnapshotSummary>` · `GET /api/v1/projects/{id}/spec/diff?from&to` → `SpecDiff` (spec-engine record, serialized as is)
- `POST /api/v1/lint` body `LintRequest` → `List<LintIssue>`

**DTOs (records, camelCase JSON):**
```java
record CreateProjectRequest(@NotNull Mode mode, @NotBlank @Pattern(regexp="[\\w.-]+/[\\w.-]+") String repo, @NotBlank String specPath,
    @NotBlank String defaultBranch, @Nullable Visibility visibility, @NotNull OutputMode outputMode, @Nullable String outputRepo) { enum Mode { EXISTING, NEW } enum Visibility { PUBLIC, PRIVATE } }
record ProjectSummary(String id, String repoFullName, @Nullable RunStatus lastRunStatus, @Nullable Instant lastRunAt, @Nullable String currentVersion) {}
record ProjectDetail(String id, String orgId, String repoFullName, String specPath, String defaultBranch, OutputMode outputMode,
    @Nullable String outputRepoFullName, WebhookStatus webhook, Instant createdAt) {}
record WebhookStatus(boolean registered, @Nullable Long webhookId) {}
record RepoOption(String fullName, String owner, boolean isPrivate, Instant pushedAt, String defaultBranch) {}
record SpecCheckResponse(boolean found, boolean valid, @Nullable String title, @Nullable String version, List<String> errors) {}
record ConfigRequest(@NotEmpty List<OutputKind> outputs, List<String> sdkLanguages, @NotBlank String outputDir, String npmScope, String pypiName,
    String mavenGroupId, String mavenArtifactId, @NotNull VersionStrategy versionStrategy, @NotNull BreakingGate breakingGate,
    @NotNull LintFailOn lintFailOn, String aiProvider, String aiModel, @Nullable String aiApiKey /* null = keep, "" = clear */) {}
record ConfigResponse(int version, /* same fields minus aiApiKey */ ..., boolean aiKeySet) {}
record SpecResponse(String content, String sha, String format) {}
record SaveSpecRequest(@NotNull String content, @NotBlank String sha, @Nullable String message, boolean generate) {}
record SaveSpecResponse(String commitSha, String newSha, @Nullable String runId, @Nullable ProblemSummary runError) {}
record ProblemSummary(String code, String detail) {}
record SnapshotSummary(String id, String contentHash, @Nullable String infoVersion, Instant createdAt) {}
record LintRequest(@NotNull @Size(max = 2_097_152) String content) {}
```

## Tasks

### Task 1: Contract + snapshot

- [ ] **Step 1: Write the failing test**

```java
@Test void createProject_validatesRepoFormat() { /* repo "not a repo" → 400 with errors[0].field == "repo" */ }
@Test void lintIs501UntilImplemented() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*CON02ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 3: Implement** — controllers + DTOs; then `scripts/export-openapi.sh && (cd frontend && pnpm gen:api && pnpm tsc --noEmit)`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*CON02ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): contract: projects, config, spec, lint"`

---

## Done when

- [ ] All tasks above are committed on `ticket/con-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
