# EmitKit v1 — Ticket Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan ticket by ticket. Each ticket is one PR. Before starting a ticket, expand it into test-first steps (failing test → run → implement → run → commit) using the ticket's **Tests** and **Produces** lines. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver EmitKit v1 (parity + core additions) on Spring Boot 4 + React as ~98 small PRs that can be built in parallel.

**Architecture:** Gradle modular monolith (`api-app`, `worker-app` + shared modules) and a fresh React SPA. Early **interface tickets** (module interfaces) and **contract tickets** (REST DTOs + stub controllers → OpenAPI → generated TS client) unblock parallel work: once they merge, backend modules, generators, worker steps and frontend pages proceed independently against interfaces, fakes and MSW mocks.

**Tech Stack:** Java 21, Spring Boot 4, Gradle (Kotlin DSL), PostgreSQL 16 + Flyway, RabbitMQ, Redis, Vault Transit, RustFS, Spring AI (Gemini), github-api, swagger-parser, JMustache, docker-java + gVisor; React 19, Vite, TanStack Router/Query, Tailwind v4, shadcn/ui, CodeMirror 6.

**Spec:** [`docs/superpowers/specs/2026-10-31-emitkit-rebuild-v1-design.md`](../specs/2026-10-31-emitkit-rebuild-v1-design.md) and [`…-overview-design.md`](../specs/2026-10-31-emitkit-rebuild-overview-design.md). Section refs below (`v1 §5.1`, `ov §4.5`) point there.

## Global Constraints

- **PR size:** ≤ 1000 changed lines (added + deleted) per PR. Excluded from the count: lockfiles (`*.lock`, `pnpm-lock.yaml`, `gradle/libs.versions.toml` is *included*), the generated client `frontend/src/lib/api/generated/**`, the exported `frontend/openapi.json`, and golden snapshot outputs under `**/src/test/resources/golden/**`. Enforced by `scripts/pr-size.sh` in CI (ticket T02). Only **CUT-01** (deleting the TS code) may carry the `size-exempt` label. If a ticket grows past the limit, split it before review, not after.
- One ticket = one PR = one reviewable unit with its own tests. CI must be green.
- Java 21, Spring Boot 4, Gradle Kotlin DSL; module dependency rules from ov §2, enforced by ArchUnit.
- IDs are ULIDs generated in Java; timestamps `timestamptz` UTC; API under `/api/v1`; errors are RFC 9457 Problem Details with a stable `code` (ov §5).
- All `*_enc` columns go through `SecretCipher` (Vault Transit, per-row context `{table}:{column}:{rowId}`) — ov §3.1.
- Frontend is built fresh: nothing copied from `apps/web`; no raw palette classes in feature code; route files ≤ 150 lines; four data states on every data view (ov §4).
- TDD: every ticket lands its tests in the same PR.

## Review Focus

These failure modes are implied by the spec but easy to miss; each has a pinned test in the owning ticket.

1. **Duplicate delivery** (RabbitMQ redelivery or GitHub webhook retry) must not open two PRs or create two versions → test `redeliveredRunMessage_opensOnePr` in **WRK-11**; `duplicateDelivery_isIgnored` in **HOOK-01**.
2. **Spec names that collide with language keywords or each other** (`operationId: class`, tags `user` and `User`, schema `Error`) must still produce code that compiles → fixture `reserved-words.yaml` in **GEN-00**, compiled for every SDK in **GEN-12**.
3. **The user who created the project loses repo access mid-run** → run fails with `VCS_AUTH` and a clear message, not a retry loop → test `revokedToken_failsWithVcsAuth_noRetry` in **WRK-03**.
4. **Two people editing the same spec** → second save gets 409 `SPEC_CONFLICT` and the UI offers Reload/Compare, never a silent overwrite → `staleSha_returns409` in **PRJ-05**, `conflictDialog_offersCompare` in **WEB-09**.
5. **Cancel while the sandbox is running** → container killed, remote branch deleted, status `CANCELLED` within 10 s → `cancelDuringSandbox_killsContainerAndCleansBranch` in **WRK-11**.

---

## How to read the tickets

Each ticket lists: **Spec**, **Depends on** (must be merged first), **Size** (estimated changed lines), **Scope** (files/modules), **Produces** (names other tickets rely on), **Tests** (what proves it).

**Lanes** (who can work in parallel): `PLAT` platform/infra · `CORE` shared backend · `SPEC` spec-engine · `GEN` generators · `VCS` GitHub · `API` api-app features · `WRK` worker · `UI` design system · `WEB` frontend pages.

### Dependency waves (summary)

| Wave | Can start when | Tickets |
|---|---|---|
| 0 | immediately | T01, T03, T04, SBX-01, SBX-02 |
| 1 | T01 merged (T04 for UI) | T02, CORE-01, CORE-02, DB-01, SPEC-01, VCS-01, UI-01, OBJ-01 |
| 2 | wave-1 interfaces merged | DB-02..04, API-00, WRK-00, SPEC-02..06, GEN-01, GEN-00, VCS-02..05, UI-02..05, SBX-03, then AI-01 |
| 3 | API-00 / GEN-01 / WRK-00 merged | CON-01..03 → AUTH-*, PRJ-*, RUN-*, WEB-*; GEN-02..12; WRK-01..10 |
| 4 | features merged | HOOK-*, WRK-11..12, WEB-14..15, OPS-* |
| 5 | parity checklist green | CUT-01 |

---

## Lane PLAT — repo, CI, local stack

### T01 · Backend monorepo skeleton
- **Spec:** ov §2 · **Depends on:** — · **Size:** ~450
- **Scope:** `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, convention plugin `backend/build-logic/` (Java 21 toolchain, Spotless, JUnit 5), empty modules `common, domain, vcs, spec-engine, generators/core, ai, api-app, worker-app` each with `build.gradle.kts`; `backend/architecture-tests/` with ArchUnit rules for ov §2.
- **Produces:** module names and package roots `dev.emitkit.{common,domain,vcs,spec,gen,ai,api,worker}`.
- **Tests:** `ModuleDependencyRulesTest` (each ov §2 rule as one ArchUnit rule; a deliberately bad import in a test fixture fails).

### T02 · CI pipeline + PR size gate
- **Spec:** ov §6 · **Depends on:** T01, T04 · **Size:** ~300
- **Scope:** `.github/workflows/backend.yml`, `frontend.yml`, `pr-size.yml`; `scripts/pr-size.sh` (counts `git diff --numstat origin/main...HEAD` minus the Global Constraints exclusions; fails > 1000 unless label `size-exempt`).
- **Tests:** `scripts/pr-size.test.sh` with fixture diffs: 999 lines pass, 1001 fail, 5000 lines of excluded paths pass, label bypass works.

### T03 · Local dev stack (docker-compose)
- **Spec:** ov §2, §3.1 · **Depends on:** — · **Size:** ~350
- **Scope:** `deploy/compose/docker-compose.yml` (postgres 16, rabbitmq 3 w/ management, redis 7, rustfs, vault dev); `deploy/compose/vault/init.sh` (enable transit, create `emitkit-secrets` `aes256-gcm96 derived=true exportable=false`, enable approle, write the two policies, create roles `emitkit-api`, `emitkit-worker`); `deploy/compose/README.md`.
- **Tests:** CI job runs `docker compose up -d --wait` then `vault write transit/encrypt/emitkit-secrets` with context succeeds and a `transit/keys/.../export` call is denied.

### T04 · Frontend scaffold
- **Spec:** ov §4.2, §4.5, §4.7 · **Depends on:** — · **Size:** ~600
- **Scope:** `frontend/` Vite + React 19 + TS strict; TanStack Router (file-based) + Query providers in `src/app/`; Tailwind v4; ESLint with `eslint-plugin-boundaries` (routes → features → ui/lib) and a custom rule banning raw palette classes (`bg-indigo-500` etc.) outside `src/ui/`; Vitest + Testing Library; MSW in `src/test/`; Playwright config; folder skeleton from ov §4.5.
- **Tests:** lint fixture files: a feature importing another feature's internals fails; `className="text-indigo-500"` in `features/` fails; a smoke Vitest renders the root route.

### OBJ-01 · Artifact storage client (RustFS / S3)
- **Spec:** v1 §5.1 step 9, §4 retention · **Depends on:** T01, T03 · **Size:** ~400
- **Scope:** package `dev.emitkit.domain.storage` in `domain` (used by api-app for download/retention and by worker-app for upload).
- **Produces:** `interface ArtifactStorage { void put(String key, Path file); InputStream get(String key); void delete(String key); List<String> listOlderThan(String prefix, Instant cutoff); }`, `S3ArtifactStorage` (AWS SDK v2, path-style, endpoint from `emitkit.storage.endpoint`).
- **Tests:** `S3ArtifactStorageIT` on Testcontainers `GenericContainer` RustFS: put/get round-trip, delete, listOlderThan.

---

## Lane CORE — shared backend

### CORE-01 · Common: errors, IDs, problem details types
- **Spec:** ov §5 · **Depends on:** T01 · **Size:** ~350
- **Produces:** `enum ErrorCode` (all codes named in v1: `SPEC_CONFLICT, SPEC_NOT_FOUND, SPEC_TOO_LARGE, SPEC_INVALID, LINT_FAILED, BREAKING_BLOCKED, GENERATOR_ERROR, CHECK_FAILED, SANDBOX_TIMEOUT, STORAGE_ERROR, VCS_CONFLICT, VCS_AUTH, VCS_RATE_LIMITED, SECRETS_UNAVAILABLE, FORBIDDEN, NOT_FOUND, VALIDATION_FAILED`), `class AppException(ErrorCode, String detail, Map<String,Object> extra)`, `Ids.newUlid(): String`, `class TransientException extends AppException` (marker for retry).
- **Tests:** `IdsTest` (26 chars, monotonic within a ms), `ErrorCodeTest` (every code has an HTTP status mapping).

### CORE-02 · SecretCipher (Vault Transit + local AES)
- **Spec:** ov §3.1 · **Depends on:** CORE-01, T03 · **Size:** ~600
- **Produces:** `interface SecretCipher { String encrypt(String, SecretContext); String decrypt(String, SecretContext); List<String> decryptBatch(List<CipherItem>); }`, `record SecretContext(String table, String column, String rowId)`, `record CipherItem(String ciphertext, SecretContext ctx)`, `VaultTransitCipher` (spring-vault-core, AppRole auth with `secret_id` read from file), `LocalAesCipher`, `SecretCipherConfig` that refuses `LocalAesCipher` under `prod`/`staging`; `SecretBackendException extends TransientException`.
- **Tests:** `VaultTransitCipherIT` (Testcontainers Vault): round-trip; ciphertext starts `vault:v1:`; **decrypt with a different `rowId` context fails**; Vault stopped → `SecretBackendException`. `SecretCipherConfigTest`: prod profile + local cipher → startup fails.

### DB-01 · Migration V1 identity + entities
- **Spec:** v1 §4 · **Depends on:** T01, CORE-01 · **Size:** ~600
- **Scope:** `domain/src/main/resources/db/migration/V1__identity.sql`; entities + Spring Data repos `User, OAuthAccount, Organization, OrgMember` (enums `Role {ADMIN, EDITOR, VIEWER}`, `RoleSource {SYNC, MANUAL}`).
- **Tests:** `IdentityRepositoriesIT` (Testcontainers Postgres): unique `(provider, provider_account_id)`, unique `(vcs_provider, vcs_org_id)`, composite PK on members.

### DB-02 · Migration V2 projects + configs
- **Spec:** v1 §4 · **Depends on:** DB-01 · **Size:** ~500
- **Scope:** `V2__projects.sql`; `Project`, `ProjectConfig` (JSONB `outputs`, `sdk_languages`; enums `OutputMode`, `VersionStrategy`, `BreakingGate`, `LintFailOn`); `ProjectConfigRepository.findLatest(projectId)`.
- **Tests:** unique `(org_id, repo_full_name)`; `findLatest` returns max version; JSONB round-trip of enum lists.

### DB-03 · Migration V3 runs, snapshots, logs
- **Spec:** v1 §4 · **Depends on:** DB-02 · **Size:** ~600
- **Scope:** `V3__runs.sql`; `SpecSnapshot`, `GenerationRun` (enums `RunStatus`, `RunTrigger`, `StepName`), `GenerationRunLog`; indexes from v1 §4.
- **Tests:** snapshot unique `(project_id, content_hash)`; `GenerationRunRepository.findLastSuccessfulNonDryRun(projectId)`; log unique `(run_id, seq)`.

### DB-04 · Migration V4 run results
- **Spec:** v1 §4 · **Depends on:** DB-03 · **Size:** ~450
- **Scope:** `V4__run_results.sql`; `RunArtifact` (JSONB `manifest`), `LintResultRow`, `RunApproval`, `SdkVersion`.
- **Tests:** unique `(project_id, version)`; one approval per run.

### API-00 · api-app skeleton
- **Spec:** ov §3 security baseline, §5; v1 §8 last row · **Depends on:** CORE-01 · **Size:** ~600
- **Scope:** `ApiApplication`; two `SecurityFilterChain`s (webhooks: `/webhooks/**` stateless, no CSRF; app: session + CSRF cookie `XSRF-TOKEN`); CORS from `emitkit.cors.allowed-origins`; `ProblemDetailsAdvice` mapping `AppException` → RFC 9457 with `code`; springdoc at `/v3/api-docs`; actuator `health` only on management port; Spring Session Redis.
- **Produces:** `@RestControllerAdvice ProblemDetailsAdvice`; property names `emitkit.cors.allowed-origins`.
- **Tests:** `ProblemDetailsAdviceTest` (code + status), `CorsTest` (allowed vs foreign origin), `CsrfTest` (POST without header → 403), `HealthTest`.

### WRK-00 · worker-app skeleton + RabbitMQ topology
- **Spec:** ov §3 messaging · **Depends on:** CORE-01 · **Size:** ~500
- **Scope:** `WorkerApplication`; `common` gets `dev.emitkit.common.messaging.Queues` constants (shared by api + worker); `RabbitTopologyConfig` declaring `emitkit.jobs` (direct), `generation.jobs`, `generation.retry.10s|60s|300s` (TTL → DLX back to jobs), `generation.dlq`, `emitkit.control` (fanout) + per-worker anonymous queue; `record GenerationJob(String runId, int attempt)`; a no-op `GenerationJobListener`.
- **Tests:** `RabbitTopologyIT` (Testcontainers RabbitMQ): message to `generation.retry.10s` reappears on `generation.jobs` after TTL (use a 1 s TTL override in test).

### AI-01 · Spring AI docs assistant
- **Spec:** v1 §6.1 · **Depends on:** GEN-01, CORE-02 · **Size:** ~550
- **Produces:** `ChatModelFactory.create(String provider, String model, String apiKey): ChatClient` (only `gemini` in v1); `GeminiDocsAiAssistant implements DocsAiAssistant` (uses `DocsExamples` from GEN-01); property `emitkit.ai.gemini.default-model`; `spring.ai.model.chat=none`.
- **Tests:** with a fake `ChatModel` returning recorded JSON: maps each operation to `DocsExamples`; malformed JSON → empty map (caller uses template); timeout after 30 s → empty map; exactly one call per tag; empty `ai_model` → default model from config.

---

## Lane SPEC — spec-engine (pure Java)

### SPEC-01 · ApiModel + naming
- **Spec:** v1 §5.2 · **Depends on:** T01 · **Size:** ~600
- **Produces:** records `ApiModel(Info info, List<Server> servers, Map<String,SecurityScheme> securitySchemes, List<Tag> tags, List<Operation> operations, Map<String,Schema> schemas)`, `Operation(...)`, `Parameter(...)` as in v1 §5.2; `sealed interface Schema permits ObjectSchema, ArraySchema, PrimitiveSchema, EnumSchema, RefSchema, OneOfSchema, AllOfSchema`; `interface NameStrategy { String type(String); String member(String); String file(String); boolean isReserved(String); }` with `CamelCase`, `SnakeCase`, `PascalCase` + reserved-word sets for TS, Python, Java.
- **Tests:** `NameStrategyTest` table: `"user-id"` → `userId`/`user_id`/`UserId`; `class` → `class_` (py) / `clazz` (java) / `_class` (ts); digits-first names get a prefix.

### SPEC-02 · Parser: operations & parameters
- **Spec:** v1 §5.1 step 2, §5.2 · **Depends on:** SPEC-01 · **Size:** ~700
- **Produces:** `SpecParser.parse(String content): ParseResult` (`ApiModel model, List<String> errors, String infoVersion, String contentHash`) — operations, params (path-level merged with op-level; op wins on `(name,in)`), request bodies, responses, security, servers, tags; synthesized `operationId` = `{method}{PathInPascalCase}`.
- **Tests:** fixtures `petstore-2.0.yaml`, `petstore-3.0.json`, `petstore-3.1.yaml` parse to the same operation list; `merge_operationLevelWins`; `missingOperationId_isSynthesized` (`GET /users/{id}` → `getUsersById`); invalid spec → errors, no exception.

### SPEC-03 · Parser: schemas
- **Spec:** v1 §5.2 · **Depends on:** SPEC-02 · **Size:** ~600
- **Scope:** map OpenAPI schemas to the sealed `Schema` types (refs kept as `RefSchema`, nullable, enums, oneOf/allOf, arrays, additionalProperties, formats).
- **Tests:** one test per Schema kind; recursive schema (`Node.children: Node[]`) doesn't loop; 3.1 `type: [string, "null"]` → nullable string.

### SPEC-04 · Linter
- **Spec:** v1 §5.3 · **Depends on:** SPEC-02 · **Size:** ~600
- **Produces:** `SpecLinter.lint(ApiModel, String rawContent): List<LintIssue>`; `record LintIssue(String ruleId, Severity severity, String jsonPath, String message)`; the nine rules with the exact ids/severities in v1 §5.3.
- **Tests:** one passing and one failing fixture per rule; `jsonPath` points at the offending node.

### SPEC-05 · Differ: operations & parameters
- **Spec:** v1 §5.4 · **Depends on:** SPEC-03 · **Size:** ~650
- **Produces:** `SpecDiffer.diff(ApiModel before, ApiModel after): SpecDiff`; `record SpecDiff(List<OpRef> added, List<OpRef> removed, List<OpChange> modified)`, `record Change(ChangeKind kind, String location, boolean breaking)`; `SpecDiff.hasBreaking()`.
- **Tests:** op removed (breaking), new required param (breaking), optional→required (breaking), param removed (breaking), body becomes required (breaking), optional param added (not), description changed (not).

### SPEC-06 · Differ: schema changes + SemVer
- **Spec:** v1 §5.4, §5.5 · **Depends on:** SPEC-05 · **Size:** ~600
- **Scope:** schema-level changes (required request property added, response property removed/added, enum value removed, type changed); `VersionCalculator.next(VersionStrategy, Optional<String> last, SpecDiff, boolean firstRun, String infoVersion): VersionResult(String version, Optional<ChangeType> type)` using java-semver.
- **Tests:** each schema rule; version table: first run → `0.1.0`; `0.4.2` + breaking → `0.5.0`; `0.4.2` + added → `0.4.3`; `1.2.3` + breaking → `2.0.0`; `1.2.3` + added → `1.3.0`; no change → same, `type` empty; `SPEC_VERSION` + `"abc"` → `0.1.0`.

---

## Lane GEN — generators

### GEN-01 · Generator SPI + template engine + golden harness
- **Spec:** v1 §6 · **Depends on:** SPEC-01 · **Size:** ~500
- **Produces:** `interface Generator { GeneratorId id(); List<GeneratedFile> generate(ApiModel, GeneratorContext); }`; `enum GeneratorId { TS_SDK, PY_SDK, JAVA_SDK, CLI, MCP, DOCS, WORKFLOWS }`; `record GeneratedFile(String path, String content, FileKind kind)`, `enum FileKind { GENERATED, CUSTOM_STUB }`; `record GeneratorContext(String outputDir, String version, PackageNames names, Optional<DocsAiAssistant> ai)`; `interface DocsAiAssistant { Map<String, DocsExamples> examples(Tag tag, List<Operation> ops); }` keyed by `operationId`, missing keys = use template; `record DocsExamples(String curl, String typescript, String python, String java, String notes)` (both live here so `ai` depends on `generators/core`, never the reverse); `Templates.render(String templatePath, Object ctx)` (JMustache, classpath); `GoldenTest` JUnit extension: renders fixtures to `src/test/resources/golden/{fixture}/…`, `-Dgolden.update=true` rewrites.
- **Tests:** a dummy generator round-trips through the golden harness; output is byte-identical across two runs.

### GEN-00 · Shared fixture specs
- **Spec:** v1 §6, ov §6 · **Depends on:** SPEC-03 · **Size:** ~700 (YAML)
- **Scope:** `generators/core/src/testFixtures/resources/specs/`: `petstore.yaml`, `auth-schemes.yaml`, `polymorphism.yaml` (oneOf/allOf), `enums.yaml`, `pagination.yaml`, `no-operation-ids.yaml`, `reserved-words.yaml` (Review Focus #2), `deep-nesting.yaml`, `multi-tag.yaml`, `big-200-ops.yaml` (generated by a script in the same PR, script counted, output excluded as golden). `FixtureSpecs.load(name): ApiModel`.
- **Tests:** every fixture parses with zero errors.

### GEN-02 · TS SDK: client, errors, package
- **Spec:** v1 §6 table · **Depends on:** GEN-01, GEN-00 · **Size:** ~600
- **Scope:** `generators/typescript-sdk`: `src/client.ts` (fetch, base URL, auth headers from securitySchemes, retries), `src/errors.ts`, `package.json` (`@{scope}/{name}`, version), `tsconfig.json`, `README.md`, `src/custom/index.ts` (`CUSTOM_STUB`).
- **Tests:** golden for `petstore`, `auth-schemes`; `customStub_hasKindCustomStub`.

### GEN-03 · TS SDK: types + resources
- **Depends on:** GEN-02 · **Size:** ~700
- **Scope:** `src/types.ts` from schemas (all Schema kinds), `src/resources/{tag}.ts` one method per operation.
- **Tests:** golden for all fixtures.

### GEN-04 · Python SDK: client, errors, pyproject
- **Depends on:** GEN-01, GEN-00 · **Size:** ~600
- **Scope:** `{pkg}/client.py` (httpx sync + async), `{pkg}/errors.py`, `pyproject.toml`, `README.md`, `{pkg}/custom/__init__.py`.
- **Tests:** golden `petstore`, `auth-schemes`.

### GEN-05 · Python SDK: Pydantic models + resources
- **Depends on:** GEN-04 · **Size:** ~700
- **Tests:** golden all fixtures.

### GEN-06 · Java SDK: build, client, exceptions
- **Depends on:** GEN-01, GEN-00 · **Size:** ~650
- **Scope:** `build.gradle.kts` (maven coords), `{Api}Client.java` (java.net.http + Jackson, builder config), `ApiException` hierarchy, `custom/` stub.
- **Tests:** golden `petstore`, `auth-schemes`.

### GEN-07 · Java SDK: models (records) + resources
- **Depends on:** GEN-06 · **Size:** ~750
- **Tests:** golden all fixtures.

### GEN-08 · CLI generator
- **Depends on:** GEN-01, GEN-00 · **Size:** ~600
- **Scope:** Commander ^11 TS CLI: `{tag} {op}` subcommands, flags from params, `--json` body, `bin` entry.
- **Tests:** golden `petstore`, `multi-tag`, `reserved-words`.

### GEN-09 · MCP server generator
- **Depends on:** GEN-01, GEN-00 · **Size:** ~600
- **Scope:** `@modelcontextprotocol/sdk` server, one tool per operation, `inputSchema` from params + body, stdio, `package.json`.
- **Tests:** golden `petstore`, `polymorphism`; every tool's `inputSchema` is valid JSON Schema (networknt validator in test).

### GEN-10 · Docs generator
- **Depends on:** GEN-01, GEN-00 · **Size:** ~700
- **Scope:** `docs/v{version}/index.md` + `{tag}.md`; params table, schemas, template examples (curl, TS, Python, Java); uses `ctx.ai()` when present, per operation falls back to template when its key is missing.
- **Tests:** golden without AI; stub `DocsAiAssistant` returning examples for 1 of 2 operations → AI text for that one, template text for the other.

### GEN-11 · Workflows generator
- **Depends on:** GEN-01 · **Size:** ~300
- **Scope:** `.github/workflows/emitkit-publish-{npm,pypi,maven}.yml` per selected language, triggered on `sdk/v*`.
- **Tests:** golden per language combination; YAML parses.

### GEN-12 · Toolchain image + compile tests
- **Spec:** v1 §5.6, ov §6 · **Depends on:** GEN-03, GEN-05, GEN-07, GEN-08, GEN-09 · **Size:** ~500
- **Scope:** `deploy/docker/toolchain/Dockerfile` (Node 20 + typescript + prettier, Python 3.12 + ruff, Temurin 21 + google-java-format, vendored jars), `check.sh` (single-run mode, no watchers, writes `report.json` `{tool, exitCode, output}`), `generators/compile-tests` module running every golden output through the image with Testcontainers.
- **Tests:** all fixtures × (TS, Python, Java, CLI, MCP) pass `tsc --noEmit --strict` / `ruff check` / `javac`; `reserved-words` included.

---

## Lane VCS — GitHub

### VCS-01 · VCS interfaces + in-memory fake
- **Spec:** v1 §7 · **Depends on:** CORE-01 · **Size:** ~550
- **Produces:** `VcsProvider`, `VcsClient` exactly as v1 §7 (incl. `deleteBranch`, `listBlobShas`); records `VcsOrg, VcsRepo, FileContent(String content, String sha), CommitRef, PullRequest, Webhook, RateLimit`; exceptions `VcsConflictException`, `VcsAuthException`, `VcsRateLimitException extends TransientException`, `VcsServerException extends TransientException`; `InMemoryVcsClient` in `testFixtures` (used by other lanes' tests).
- **Tests:** `InMemoryVcsClientTest` covering putFile sha conflict and commitTree.

### VCS-02 · GitHub client: identity, repos, files
- **Depends on:** VCS-01 · **Size:** ~750
- **Scope:** `GitHubVcsProvider`, `GitHubVcsClient`: `listOrgs`, `listRepos`, `createRepo`, `getFile`, `putFile` (409/422 sha mismatch → `VcsConflictException`), `rateLimit`, error mapping (401/403 → auth, 5xx → server). OkHttp connector with cache.
- **Tests:** WireMock suite `GitHubVcsClientFilesTest`; a shared `VcsClientContractTest` abstract class run against both `InMemoryVcsClient` and `GitHubVcsClient`.

### VCS-03 · GitHub client: git data, PRs, tags
- **Depends on:** VCS-02 · **Size:** ~700
- **Scope:** `createBranch`, `deleteBranch`, `commitTree` (blobs → tree → commit → ref update), `listBlobShas` (recursive tree call filtered by prefix), `openPr`, `comment`, `createTag` (existing tag → no-op returning false).
- **Tests:** WireMock: commitTree issues exactly one tree + one commit call for 50 files; contract tests extended.

### VCS-04 · Webhooks register/delete + HMAC verifier
- **Depends on:** VCS-02 · **Size:** ~400
- **Produces:** `registerWebhook`, `deleteWebhook`; `WebhookVerifier.verify(byte[] body, String signatureHeader, String secret): boolean` (constant-time).
- **Tests:** known GitHub test vector verifies; one-byte change fails; missing `sha256=` prefix fails.

### VCS-05 · Rate-limit tracking
- **Spec:** v1 §7 rate limits · **Depends on:** VCS-02 · **Size:** ~500
- **Produces:** `RateLimitTracker` (records headers to Redis `ratelimit:{userId}`); `RateLimitPolicy.BACKGROUND` (wait until reset ≤ 5 min, else throw) vs `INTERACTIVE` (throw `VCS_RATE_LIMITED` with reset time) applied by a decorator `RateLimitedVcsClient`.
- **Tests:** remaining 99 + INTERACTIVE → `VCS_RATE_LIMITED`; remaining 99 + BACKGROUND + reset in 2 s → waits then proceeds (clock injected); reset in 10 min → `VcsRateLimitException`.

---

## Lane API — contracts and api-app features

> **Contract tickets** define DTO records + controller methods returning **501**, export `frontend/openapi.json`, and regenerate the TS client. Feature tickets then replace the 501 bodies. A CI check fails if `openapi.json` is stale.

### CON-01 · Contract: me, orgs, members
- **Spec:** v1 §8 rows `/me*`, `/orgs*`, members · **Depends on:** API-00, T04 · **Size:** ~500
- **Tests:** `OpenApiSnapshotTest` (doc matches committed `openapi.json`); generated client compiles in `frontend`.

### CON-02 · Contract: projects, config, spec, lint
- **Spec:** v1 §8 · **Depends on:** CON-01 · **Size:** ~650

### CON-03 · Contract: runs, logs, files, artifact, versions
- **Spec:** v1 §8 · **Depends on:** CON-01 · **Size:** ~600
- **Note:** SSE endpoint documented as `text/event-stream` with event schema `{seq, ts, level, step, message}` and terminal event `done`.

### AUTH-01 · GitHub OAuth login + /me
- **Spec:** ov §3 security, v1 §8 `/me` · **Depends on:** CON-01, DB-01, CORE-02 · **Size:** ~700
- **Scope:** `oauth2Login` with scopes `repo, read:org, workflow, user:email, write:repo_hook`; `GitHubUserService` upserts `users` + `oauth_accounts` (token via `SecretCipher`, context `oauth_accounts:access_token_enc:{id}`); token re-saved on every login; `GET /me`; logout; cookie `HttpOnly; Secure; SameSite=Lax`; login success redirect honours `next` (same-origin only).
- **Tests:** `OAuthLoginIT` with WireMock GitHub OAuth: first login creates user; second login updates token; `next=https://evil.com` ignored.

### AUTH-02 · Org sync
- **Spec:** v1 §3 mapping · **Depends on:** AUTH-01, VCS-02 · **Size:** ~600
- **Produces:** `OrgSyncService.sync(String userId)`; triggered after login and by `POST /me/sync-orgs`.
- **Tests:** owner → ADMIN, member → EDITOR, personal org created with ADMIN; a `MANUAL` role is not changed by sync; user removed from a GitHub org loses `SYNC` membership but keeps `MANUAL`.

### AUTH-03 · OrgAccessService + orgs & members endpoints
- **Spec:** v1 §3 · **Depends on:** AUTH-01 · **Size:** ~650
- **Produces:** `enum Permission` (all of v1 §3), `OrgAccessService.require(String orgId, Permission p): Membership` (non-member → `NOT_FOUND`, low role → `FORBIDDEN`), `ProjectAccess.require(String projectId, Permission p): Project`; implements `GET /orgs`, `GET /orgs/{id}`, `GET/PATCH /orgs/{id}/members/{userId}` (sets `MANUAL`).
- **Tests:** permission matrix test generated from the v1 §3 table (every role × permission); non-member gets 404 not 403; admin can't demote the last admin (409 `VALIDATION_FAILED`).

### PRJ-01 · Create project (existing repo) + repo picker + spec-check
- **Spec:** v1 §8, §10.2 · **Depends on:** AUTH-03, VCS-04, DB-02, CON-02 · **Size:** ~800
- **Scope:** `GET /orgs/{orgId}/vcs-repos`, `GET …/spec-check`, `POST /orgs/{orgId}/projects` (`mode=EXISTING`): validate, insert project + config v1 (defaults), register webhook (events `push`, `pull_request`) with a random 32-byte secret encrypted, unique per org.
- **Tests:** happy path writes project + config + webhook id; duplicate repo → 409; webhook registration fails → project not created (transaction + compensating delete).

### PRJ-02 · Create project (new repo)
- **Depends on:** PRJ-01 · **Size:** ~350
- **Scope:** `mode=NEW`: `createRepo` + starter `openapi.yaml` (OpenAPI 3.1, title = repo name) + webhook.
- **Tests:** starter spec parses with `SpecParser`; repo name taken → 409.

### PRJ-03 · Project get/list/delete + webhook re-register
- **Depends on:** PRJ-01 · **Size:** ~450
- **Tests:** delete removes webhook; webhook delete fails → project still deleted, warning logged; `POST /projects/{id}/webhook` rotates the secret.

### PRJ-04 · Config get/put
- **Spec:** v1 §4 project_configs, §8 · **Depends on:** PRJ-01 · **Size:** ~550
- **Tests:** PUT creates version n+1; GET never returns the key, returns `aiKeySet`; outputs contains SDK with empty languages → `VALIDATION_FAILED`; EDITOR → 403.

### PRJ-05 · Spec get/put
- **Spec:** v1 §8 spec rows · **Depends on:** PRJ-01 · **Size:** ~400
- **Tests:** get returns content/sha/format; `staleSha_returns409` (Review Focus #4); success returns commit sha.

### PRJ-06 · Lint endpoint, snapshots, spec diff
- **Spec:** v1 §8 `/lint`, `/spec/snapshots`, `/spec/diff` · **Depends on:** SPEC-04, SPEC-06, DB-03, CON-02 · **Size:** ~450
- **Tests:** `/lint` on invalid YAML → parse error as an ERROR issue at line; diff between two stored snapshots matches `SpecDiffer`.

### RUN-01 · Create/list/get runs + enqueue
- **Spec:** v1 §8 runs rows · **Depends on:** AUTH-03, DB-04, WRK-00, CON-03 · **Size:** ~650
- **Produces:** `RunService.create(String projectId, RunTrigger trigger, boolean dryRun, String userId): GenerationRun` (uses latest config, publishes `GenerationJob` after commit via `TransactionSynchronization`).
- **Tests:** message published only after commit (rollback → no message); list paginates by cursor.

### RUN-02 · Cancel, rerun, approval endpoints
- **Spec:** v1 §5.1 step 6, §8 · **Depends on:** RUN-01 · **Size:** ~550
- **Tests:** cancel on SUCCESS → 409; cancel publishes to `emitkit.control`; rerun copies config version + sets `parent_run_id`; approve re-enqueues; reject → CANCELLED; EDITOR approving → 403.

### RUN-03 · Logs + SSE stream
- **Spec:** ov §3 live logs · **Depends on:** RUN-01, WRK-02 · **Size:** ~550
- **Tests:** stream replays stored lines then live Redis lines in `seq` order without duplicates; closes on `done`; client disconnect unsubscribes.

### RUN-04 · Files preview, artifact download, lint results, versions
- **Spec:** v1 §8 · **Depends on:** RUN-01, OBJ-01 · **Size:** ~700
- **Scope:** `GET /runs/{id}/files` (manifest), `…/files/{path}?diff=true` (unified diff via java-diff-utils against base content), `GET /runs/{id}/artifact` (streams zip), `GET /runs/{id}/lint`, `GET /projects/{id}/versions`.
- **Tests:** diff output for a MODIFIED file; path traversal `../` → 400; artifact missing (expired) → 404 with code `NOT_FOUND`.

### RUN-05 · Save spec with generate=true
- **Spec:** v1 §8 note · **Depends on:** PRJ-05, RUN-01 · **Size:** ~250
- **Tests:** commit ok + enqueue fails → 202 with `runError`; both ok → 200 with run id.

### HOOK-01 · Webhook endpoint core
- **Spec:** v1 §9 · **Depends on:** VCS-04, PRJ-01, CORE-02 · **Size:** ~550
- **Tests:** valid signature for one of two candidate projects → matched; no match → 401; `duplicateDelivery_isIgnored` (same `X-GitHub-Delivery` within 24 h); Vault down → 503.

### HOOK-02 · Push trigger + merge tagging
- **Spec:** v1 §9 · **Depends on:** HOOK-01, RUN-01, VCS-03 · **Size:** ~500
- **Tests:** push to default branch touching spec → one WEBHOOK run; other branch → ignored; spec not touched → ignored; merged `emitkit/run-{id}` PR → `sdk/v{x}` tag, plus `mcp/v{x}` when MCP output; tag exists → 200.

### OPS-03 · Retention + key re-wrap jobs
- **Spec:** v1 §4 retention, ov §3.1 rotation · **Depends on:** DB-04, OBJ-01, CORE-02 · **Size:** ~450
- **Scope:** ShedLock setup (table migration `V5__shedlock.sql`); nightly retention (logs > 90 d, artifacts > 30 d); re-wrap job (batches of 200 via `transit/rewrap`, reports rows below latest version).
- **Tests:** retention deletes only old rows/objects; re-wrap updates ciphertext version and plaintext is unchanged after decrypt.

---

## Lane WRK — pipeline

### WRK-01 · Pipeline core
- **Spec:** v1 §5.1 · **Depends on:** WRK-00, DB-04 · **Size:** ~700
- **Produces:** `interface PipelineStep { StepName name(); StepResult execute(RunContext ctx); }`; `sealed interface StepResult permits Continue, Park, Fail`; `RunContext` (run, project, config, `VcsClient vcs()`, `RunLogger log()`, `void checkCancelled()`, typed step outputs `put/get`); `PipelineRunner.run(String runId)`: loads run, skips steps whose output is persisted, saves `current_step` + output after each step, sets `RUNNING/SUCCESS/FAILED`.
- **Tests:** with fake steps: order kept; step 3 persisted → resume starts at 4; `Fail` sets `failure_code`; `Park` stops without status change beyond what the step set.

### WRK-02 · RunLogger
- **Spec:** ov §3 live logs · **Depends on:** WRK-01 · **Size:** ~450
- **Produces:** `RunLogger.info/warn/error(String message)`, `step(StepName)`, `done()`; publishes JSON `{seq, ts, level, step, message}` to `run-logs:{runId}`; flushes to Postgres every 2 s and at step end.
- **Tests:** seq strictly increasing; flush at step end; `done()` publishes the terminal event.

### WRK-03 · Steps: FetchSpec + ParseSpec
- **Spec:** v1 §5.1 steps 1–2 · **Depends on:** WRK-01, VCS-05, SPEC-03 · **Size:** ~550
- **Tests:** > 2 MB → `SPEC_TOO_LARGE`; bad `owner/repo` → `SPEC_NOT_FOUND`; snapshot reused when hash matches; `revokedToken_failsWithVcsAuth_noRetry` (Review Focus #3).

### WRK-04 · Steps: LintSpec + DiffSpec + CalcVersion
- **Spec:** v1 §5.1 steps 3–5 · **Depends on:** WRK-03, SPEC-04, SPEC-06 · **Size:** ~500
- **Tests:** `lint_fail_on=ERROR` + error issue → `LINT_FAILED`; diff baseline ignores dry runs; first run → `0.1.0`.

### WRK-05 · Step: BreakingGate
- **Spec:** v1 §5.1 step 6 · **Depends on:** WRK-04 · **Size:** ~400
- **Tests:** ALLOW continues; BLOCK → `BREAKING_BLOCKED`; REQUIRE_APPROVAL → `AWAITING_APPROVAL` + approval row + `Park`; resumed run continues at step 7.

### WRK-06 · Step: RunGenerators
- **Spec:** v1 §5.1 step 7 · **Depends on:** WRK-01, GEN-01, AI-01 · **Size:** ~400
- **Scope:** `GeneratorRegistry` (Spring beans by `GeneratorId`), selection from config outputs + languages, virtual-thread executor, `DocsAiAssistant` only when an AI key is set.
- **Tests:** only selected generators run; one throws → `GENERATOR_ERROR` naming it; AI key absent → `ctx.ai()` empty.

### WRK-07 · Step: SyntaxCheck
- **Spec:** v1 §5.1 step 8 · **Depends on:** WRK-01, SBX-03 · **Size:** ~400
- **Tests:** formatted files replace originals; failing `report.json` → `CHECK_FAILED` with tool output in logs; sandbox timeout → `SANDBOX_TIMEOUT`.

### WRK-08 · Step: PackageArtifact
- **Spec:** v1 §5.1 step 9 · **Depends on:** WRK-01, OBJ-01, VCS-03 · **Size:** ~500
- **Tests:** manifest statuses from `listBlobShas` (git blob sha computed locally: `sha1("blob {len}\0{content}")`); existing `custom/` file → SKIPPED; zip contains all non-skipped files.

### WRK-09 · Step: CommitOutput
- **Spec:** v1 §5.1 step 10 · **Depends on:** WRK-08, GEN-11 · **Size:** ~650
- **Tests:** dry run → skipped; APPEND targets same repo, SEPARATE targets output repo; branch `emitkit/run-{runId}`; PR title `EmitKit: SDK v{version}`; breaking → comment posted; SKIPPED files not committed.

### WRK-10 · Step: Finalize
- **Spec:** v1 §5.1 step 11 · **Depends on:** WRK-09 · **Size:** ~250
- **Tests:** version changed + not dry run → `sdk_versions` row; unchanged → none; `done()` called.

### WRK-11 · Retry, DLQ, cancellation, idempotency
- **Spec:** ov §3 messaging, v1 §5.1 · **Depends on:** WRK-10, RUN-02 · **Size:** ~650
- **Scope:** listener wires `PipelineRunner`; `TransientException` → next retry queue by attempt; after 3 → DLQ + `FAILED`; control-queue listener flips in-memory cancel flags; cancel cleanup (kill sandbox, `deleteBranch`).
- **Tests:** `redeliveredRunMessage_opensOnePr` (Review Focus #1); 3 transient failures → DLQ + FAILED; `cancelDuringSandbox_killsContainerAndCleansBranch` (Review Focus #5).

### WRK-12 · Pipeline end-to-end test
- **Spec:** v1 §12 · **Depends on:** WRK-11, GEN-12, HOOK-02 · **Size:** ~500
- **Tests:** Testcontainers (Postgres, RabbitMQ, Redis, Vault, RustFS) + WireMock GitHub + real toolchain: `petstore` with all outputs → SUCCESS, PR opened, artifact stored; `big-200-ops` finishes under 3 min.

---

## Lane SBX — sandbox

### SBX-01 · Spike: gVisor + rootless Docker
- **Spec:** v1 §5.6 · **Depends on:** — · **Size:** ~150 (docs + script)
- **Scope:** `docs/spikes/gvisor-rootless.md` with the result, `deploy/sandbox/setup-rootless-gvisor.sh`. Decide: rootless + runsc **or** fallback rootful sandbox daemon + runsc (v1 §5.6). Check `--cpus/--memory/--pids-limit` under cgroup v2 delegation.
- **Tests:** the script's self-check (`docker --host … run --runtime=runsc hello-world` and `dmesg` shows gVisor).

### SBX-02 · Toolchain `check.sh` contract
- **Depends on:** — · **Size:** ~200
- **Scope:** `deploy/docker/toolchain/check.sh` input layout (`/work/in.tar` → `/work/src`), output (`/work/out.tar`, `/work/report.json`), exit codes. (GEN-12 builds the image around it.)
- **Tests:** shellcheck; bats tests with stub tools.

### SBX-03 · DockerSandboxRunner
- **Spec:** v1 §5.6 · **Depends on:** SBX-01, SBX-02, CORE-01 · **Size:** ~750
- **Produces:** `interface SandboxRunner { SandboxResult run(SandboxRequest req, CancellationToken token); }`, `record SandboxRequest(String image, Path inputTar, Duration timeout)`, `record SandboxResult(Path outputTar, String reportJson, boolean timedOut)`; `DockerSandboxRunner` with all v1 §5.6 flags, `withRuntime(runtime)`, semaphore `emitkit.sandbox.max-concurrent=4`, cleanup sweep, startup runtime check (prod refuses without `runsc`).
- **Tests:** escape-attempt suite under runsc in CI: no network, read-only outside `/work`, uid 10001, killed at timeout, pids limit, `dmesg` shows gVisor; prod profile without runsc → startup fails.

---

## Lane UI — design system

### UI-01 · Tokens, theme, Storybook, base atoms
- **Spec:** ov §4.3 · **Depends on:** T04 · **Size:** ~700
- **Scope:** `src/ui/tokens.css` (all tokens, light + dark), Tailwind theme mapping, `ThemeProvider` (system/light/dark), Inter + JetBrains Mono, Storybook with both themes, Playwright visual snapshots of stories; components `Button`, `IconButton`, `Badge`, `StatusPill` (success/running/failed/awaiting/cancelled/queued, icon + label), `Kbd`.
- **Tests:** visual snapshots; `StatusPill` renders a text label for every status (not color-only); axe on stories.

### UI-02 · Form controls
- **Depends on:** UI-01 · **Size:** ~650
- **Scope:** `Input`, `Textarea`, `Select`, `Checkbox`, `Switch`, `Label`, `FieldError`, `SecretField` (write-only "set" state), `FormField` wrapper for react-hook-form.
- **Tests:** label association, error announced via `aria-describedby`, keyboard toggling.

### UI-03 · Overlays & feedback
- **Depends on:** UI-01 · **Size:** ~600
- **Scope:** `Dialog`, `ConfirmDialog` (typed confirmation option), `Sheet`, `DropdownMenu`, `Tooltip`, `Toast` (sonner styled).
- **Tests:** focus trap and return focus; Escape closes; typed-confirmation disables confirm until matched.

### UI-04 · Data display & states
- **Depends on:** UI-01 · **Size:** ~750
- **Scope:** `Table`, `DataList` (mobile), `Skeleton`, `EmptyState`, `ErrorState` (message + mono code + Retry), `NoAccessState`, `CodeBlock` (copy), `PageHeader`, `Breadcrumbs`, `RouteTabs` (links, not state), `CopyButton`.
- **Tests:** `RouteTabs` marks active route with `aria-current`; `ErrorState` shows code; table → list below 768 px (visual).

### UI-05 · Command palette
- **Spec:** ov §4.4 · **Depends on:** UI-03 · **Size:** ~450
- **Scope:** `CommandPalette` (cmdk) with groups and a registration hook `useCommands(commands)`; `ShortcutsProvider` (`g p`, `g r`, `/`, `?`, `⌘K`).
- **Tests:** ⌘K opens; typing filters; Enter runs; `?` shows help dialog.

---

## Lane WEB — pages

### WEB-01 · API & auth layer
- **Spec:** ov §4.5 · **Depends on:** T04, CON-01 · **Size:** ~550
- **Produces:** `lib/api/client.ts` (generated client + CSRF header + credentials), `queryKeys` factory, `toAppError(problem)`, `errorMessages: Record<ErrorCode, string>` (plain-language copy per code), `useMe()`, `usePermission(perm, orgId)`, `<Can>`, 401 → `/login?next=`.
- **Tests:** MSW: 401 redirects with `next`; problem with `SPEC_CONFLICT` → mapped message; `usePermission` matrix for 3 roles.

### WEB-02 · App shell + login
- **Spec:** ov §4.4, v1 §10.1 · **Depends on:** WEB-01, UI-03, UI-04 · **Size:** ~800
- **Scope:** `/login`, auth guard layout, sidebar (org switcher, nav), top bar (breadcrumbs, quota indicator < 20 %, theme toggle, user menu), mobile drawer, `/` redirect via `localStorage` last org.
- **Tests:** guard redirects; org switch updates URL; quota indicator hidden at 50 %, shown at 10 %; drawer at 360 px.

### WEB-03 · Command palette wiring + shortcuts
- **Depends on:** WEB-02, UI-05 · **Size:** ~350
- **Tests:** palette lists projects from query cache; actions hidden without permission.

### WEB-04 · Members + org settings
- **Spec:** v1 §10.1 · **Depends on:** WEB-02, CON-01 · **Size:** ~500
- **Tests:** admin changes role inline; non-admin sees read-only list; re-sync button calls endpoint and shows toast.

### WEB-05 · Projects list
- **Depends on:** WEB-02, CON-02 · **Size:** ~450
- **Tests:** search + filter in URL search params; empty state links to new-project; four states.

### WEB-06 · New-project flow
- **Spec:** v1 §10.2 · **Depends on:** WEB-05, UI-02 · **Size:** ~800
- **Tests:** step in URL; back/refresh keeps step; spec-check result shown; separate-repo picker; finish navigates to config.

### WEB-07 · Project overview, settings, versions
- **Spec:** v1 §10.3, §10.1 · **Depends on:** WEB-05, CON-03 · **Size:** ~800
- **Tests:** overview cards from mocked data; delete needs typed confirmation; versions expand changelog.

### WEB-08 · Spec editor
- **Spec:** v1 §10.4 · **Depends on:** WEB-07, UI-02 · **Size:** ~850
- **Scope:** CodeMirror (lazy-loaded), YAML/JSON, debounced validation, lint markers + Problems panel, Save/⌘S, Save & Generate, Dry run, unsaved-changes guard.
- **Tests:** problem click moves cursor to line; navigation with unsaved edits prompts; ⌘S calls save.

### WEB-09 · Conflict merge + spec diff page
- **Spec:** v1 §10.4, §10.1 diff route · **Depends on:** WEB-08 · **Size:** ~650
- **Tests:** `conflictDialog_offersCompare` (Review Focus #4); diff page `?from=&to=` drives the view; breaking changes listed first.

### WEB-10 · Config form
- **Spec:** v1 §10.5 · **Depends on:** WEB-07, UI-02 · **Size:** ~800
- **Tests:** language fields appear only with SDK; package fields per language; AI key "set" + Replace; read-only for EDITOR; unsaved guard.

### WEB-11 · Runs list
- **Depends on:** WEB-07, CON-03 · **Size:** ~450
- **Tests:** filters in URL; Trigger / Dry run buttons by permission; cursor pagination.

### WEB-12 · Live logs (useEventStream + LogView)
- **Spec:** v1 §10.6 logs, ov §4.5 live data · **Depends on:** WEB-01, CON-03 · **Size:** ~700
- **Produces:** `useEventStream(url)`, `LogView` (virtualized, step/level filter, search, pause-on-scroll, Jump to latest, download).
- **Tests:** reconnect de-duplicates by `seq`; "Reconnecting…" banner; 100k lines render without freezing (perf test with budget).

### WEB-13 · Run page: header, timeline, tabs, approval
- **Spec:** v1 §10.6 · **Depends on:** WEB-12, WEB-11 · **Size:** ~850
- **Scope:** header + actions, step timeline (horizontal/vertical), child routes `/logs`, `/files` (tree + diff), `/lint`, `/changes`, approval banner.
- **Tests:** clicking a step filters logs; actions shown per status + permission; approve/reject with comment.

### WEB-14 · Accessibility, mobile, performance pass
- **Spec:** ov §4.6 · **Depends on:** WEB-13, WEB-10, WEB-09, WEB-04, WEB-06 · **Size:** ~400
- **Scope:** fixes from audit; bundle-size check in CI (initial JS < 250 KB gzip).
- **Tests:** `@axe-core/playwright` on every v1 route → zero serious/critical.

### WEB-15 · E2E parity suite
- **Spec:** v1 §12 · **Depends on:** WEB-14, WRK-12 · **Size:** ~800
- **Tests:** Playwright against docker-compose + WireMock GitHub: one test per parity checklist line (v1 §12).

---

## Lane OPS — production & cutover

### OPS-01 · Production images + compose + Caddy
- **Spec:** ov §3, §3.1 · **Depends on:** API-00, WRK-00, T04, SBX-01 · **Size:** ~650
- **Scope:** Dockerfiles api/worker/web (multi-stage, non-root), `docker-compose.prod.yml` (api ×2, worker ×2, Caddy with TLS and SSE `flush_interval -1` on log/notification paths, Vault server mode + Raft volume, Docker secrets for AppRole `secret_id`), sandbox daemon socket mount, internal networks.
- **Tests:** CI `docker compose -f … config`; smoke job boots the stack and hits `/actuator/health` through Caddy.

### OPS-02 · Backups + restore drill
- **Spec:** v1 §11 · **Depends on:** OPS-01 · **Size:** ~450
- **Scope:** `pg_dump` + WAL archive, Vault Raft snapshot every 6 h, RustFS bucket mirror, all off-host; `scripts/restore-drill.sh`; `docs/runbooks/restore.md`.
- **Tests:** restore drill script runs in CI against compose and verifies row counts + a Transit decrypt.

### OPS-04 · Load test + security checks
- **Spec:** v1 §11 · **Depends on:** WRK-12, OPS-01 · **Size:** ~400
- **Scope:** k6 scripts (API p95 < 300 ms non-VCS; 8 concurrent runs); OWASP dependency-check + `npm audit` in CI with fail threshold.
- **Tests:** k6 thresholds encoded in the script; CI fails on high CVEs.

### CUT-01 · Delete the TypeScript code (size-exempt)
- **Spec:** v1 §1 exit 5 · **Depends on:** WEB-15, WRK-12, OPS-02, OPS-04, parity checklist signed off · **Size:** large (deletion only)
- **Scope:** remove `apps/`, `packages/`, `bun.lock`, `bunfig.toml`, `turbo.json`, `bts.jsonc`, root `package.json`/`tsconfig.json`, `start-prod.sh`, old CI workflow; rewrite root `README.md` for the new stack (README rewrite ≤ 300 lines, counted separately in the PR description).
- **Tests:** CI green with only the new stack.

---

## Self-review notes

- **Spec coverage:** every v1 §2 scope item maps to a ticket. Feature 75 → VCS-05 + WEB-02; 77 → CORE-02; 78 → API-00; 79 → API-00; 80 → UI-01..04, WEB-14. Parity checklist → WEB-15.
- **Interfaces:** names used across lanes are defined once in the **Produces** line of: CORE-01, CORE-02, OBJ-01, SPEC-01/02/04/05/06, GEN-01, VCS-01, WRK-01/02, SBX-03, RUN-01, WEB-01.
- **Size:** largest estimates are ~850 (WEB-08, WEB-13). If either crosses 1000 during work, split the tests-heavy half out (e.g. WEB-13 → header/timeline vs tabs/approval).
- **Not in v1:** feature flags table (needed from v2) — added in the first v2 ticket.
