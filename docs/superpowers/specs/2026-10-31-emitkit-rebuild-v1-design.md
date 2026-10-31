# EmitKit Rebuild — v1: Parity + Core Additions

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Depends on:** [Overview & shared architecture](2026-10-31-emitkit-rebuild-overview-design.md)

---

## 1. Goal & exit criteria

v1 rebuilds every feature of the current TypeScript EmitKit on Spring Boot + React and adds the core improvements listed below. It ships to production on Docker Compose and replaces the TypeScript code.

**Exit criteria**
1. Every item in the parity checklist (§12) passes in staging.
2. The Playwright end-to-end suite passes against staging.
3. Generator golden tests and compile tests pass for TS, Python and Java.
4. Production runs on Docker Compose (`docker-compose.prod.yml`) with 2 `api-app` and 2 `worker-app` containers behind Caddy.
5. A single PR deletes `apps/`, `packages/` and the Bun/Turbo tooling.
6. The new frontend meets overview §4: zero serious/critical axe violations on every v1 page, all four data states present on every data view, Storybook visual tests passing in both themes.

---

## 2. Scope

Numbers refer to the master feature list. New features are marked 🆕.

| Group | Features in v1 |
|---|---|
| Auth & access | 1 GitHub OAuth · 2 secure sessions + token refresh · 3 user profile |
| Orgs & teams | 5 org sync + personal org · 6 org switcher · 7 org dashboard · 8 org isolation · 🆕 9 roles (admin/editor/viewer) |
| Projects | 12 from existing repo · 13 create new repo · 14 append/separate output · 15 auto webhook · 16 delete + webhook cleanup · 17 one repo per org · 18 project tabs |
| Spec editing | 21 editor + live validation · 22 save with optimistic lock · 23 save & generate · 🆕 24 linting · 🆕 25 spec diff viewer |
| Config | 27 outputs · 28 SDK languages: TS, Python, 🆕 Java · 29 output dir, npm scope, PyPI name, 🆕 Maven coords · 30 version strategy · 31 AI key · 🆕 32 breaking-change gate |
| Runs | 34 manual + webhook triggers · 35 status lifecycle (🆕 cancelled, awaiting approval) · 36 list + detail · 37 SSE logs · 🆕 38 rerun/cancel/retry · 🆕 39 dry run · 🆕 40 preview · 🆕 41 zip download |
| Pipeline & generators | 44–48 pipeline · 49 TS SDK · 50 Python SDK · 🆕 51 Java SDK · 52 CLI · 53 MCP · 54 docs (+ AI examples) |
| Releases | 60 version history · 61 auto tags · 62 CI workflows (🆕 + Maven) · 🆕 63 breaking-change PR comment |
| Integrations | 67 HMAC webhooks · 68 push triggers · 69 merge → tag · 🆕 70 `VcsProvider` abstraction |
| Observability | 🆕 75 GitHub rate-limit awareness (76 metrics/tracing/structured logs moved to v4) |
| Platform | 77 encryption · 78 CORS allowlist · 79 health + OpenAPI reference · 80 theme, toasts, skeletons, responsive |

**Not in v1** (deferred to v2/v3): 4, 10, 11, 19, 20, 26, 33, 42, 43, 51 (Go/C#/Kotlin), 55–59, 64–66, 70 (GitLab/Bitbucket), 71–74. **Deferred to v4:** 76 (metrics, tracing, structured logs) and Kubernetes deployment.

---

## 3. Roles & permissions (🆕 9)

GitHub org sync maps GitHub org **owners → ADMIN** and **members → EDITOR**. The owner of a personal org is ADMIN. Admins can change roles in EmitKit; a role set in EmitKit overrides the synced role, and later syncs leave it alone (`role_source = MANUAL`).

| Permission | ADMIN | EDITOR | VIEWER |
|---|:-:|:-:|:-:|
| `ORG_VIEW`, `PROJECT_VIEW`, `RUN_VIEW`, `SPEC_VIEW` | ✓ | ✓ | ✓ |
| `PROJECT_CREATE` | ✓ | ✓ | |
| `PROJECT_DELETE` | ✓ | | |
| `CONFIG_EDIT` (incl. AI key, gate policy) | ✓ | | |
| `SPEC_EDIT` | ✓ | ✓ | |
| `RUN_TRIGGER`, `RUN_CANCEL`, `RUN_RERUN` | ✓ | ✓ | |
| `RUN_APPROVE` (breaking-change gate) | ✓ | | |
| `MEMBER_ROLE_EDIT` | ✓ | | |

A request for an org the user doesn't belong to returns **404**, so the response doesn't reveal that the org exists. A request with too low a role returns **403** with `code = FORBIDDEN`.

---

## 4. Data model (Flyway `V1__init.sql`)

All IDs are ULID `varchar(26)`. All timestamps are `timestamptz`. `*_enc` columns hold Vault Transit ciphertext (`vault:v{n}:…`) as `text`, encrypted with a per-row context (overview §3.1). Because the context includes the row id, the id must be generated before the secret is encrypted. ULIDs are generated in Java, so this is always true.

```
users(id, github_id UNIQUE, login, name, email, avatar_url, created_at, updated_at)

oauth_accounts(id, user_id FK, provider, provider_account_id,
               access_token_enc, scopes, updated_at,
               UNIQUE(provider, provider_account_id))

organizations(id, vcs_provider DEFAULT 'github', vcs_org_id, slug, name,
              is_personal, owner_user_id FK NULL, created_at,
              UNIQUE(vcs_provider, vcs_org_id))

org_members(org_id FK, user_id FK, role ENUM(ADMIN,EDITOR,VIEWER),
            role_source ENUM(SYNC,MANUAL), PRIMARY KEY(org_id, user_id))

projects(id, org_id FK, repo_full_name, spec_path, default_branch,
         output_mode ENUM(APPEND,SEPARATE), output_repo_full_name NULL,
         webhook_id NULL, webhook_secret_enc NULL,
         created_by FK, created_at, updated_at,
         UNIQUE(org_id, repo_full_name))

project_configs(id, project_id FK, version INT,         -- append-only; latest = max(version)
                outputs JSONB,                          -- ["SDK","CLI","MCP","DOCS"]
                sdk_languages JSONB,                    -- ["typescript","python","java"]
                output_dir DEFAULT '.emitkit/',
                npm_scope, pypi_name, maven_group_id, maven_artifact_id,
                version_strategy ENUM(EMITKIT_MANAGED,SPEC_VERSION),
                breaking_gate ENUM(ALLOW,REQUIRE_APPROVAL,BLOCK) DEFAULT 'ALLOW',
                lint_fail_on ENUM(NEVER,ERROR,WARNING) DEFAULT 'NEVER',
                ai_provider DEFAULT 'gemini', ai_model NULL, ai_api_key_enc NULL,
                created_by FK, created_at,
                UNIQUE(project_id, version))

spec_snapshots(id, project_id FK, content_hash, raw_content TEXT,
               api_model JSONB, info_version, created_at,
               UNIQUE(project_id, content_hash))

generation_runs(id, project_id FK, config_id FK, spec_snapshot_id FK NULL,
                trigger ENUM(MANUAL,WEBHOOK,RERUN), triggered_by_user_id FK NULL,
                parent_run_id FK NULL,                 -- set for reruns
                dry_run BOOL DEFAULT false,
                status ENUM(QUEUED,RUNNING,AWAITING_APPROVAL,SUCCESS,FAILED,CANCELLED),
                current_step, failure_code NULL, failure_message NULL,
                cancel_requested BOOL DEFAULT false, attempt INT DEFAULT 0,
                commit_sha, sdk_version NULL, change_type NULL,
                diff_summary JSONB NULL,
                branch_name NULL, pr_url NULL, pr_number NULL,
                created_at, started_at NULL, finished_at NULL)

generation_run_logs(id BIGSERIAL, run_id FK, seq INT, ts, level, step, message,
                    UNIQUE(run_id, seq))

run_artifacts(id, run_id FK UNIQUE, storage_key, size_bytes, file_count,
              manifest JSONB,                          -- [{path, sha256, size, status: ADDED|MODIFIED|UNCHANGED|SKIPPED}]
              created_at)

lint_results(id, run_id FK, rule_id, severity ENUM(ERROR,WARNING,INFO),
             json_path, message)

run_approvals(id, run_id FK UNIQUE, decided_by FK NULL,
              decision ENUM(APPROVED,REJECTED) NULL, comment, created_at, decided_at)

sdk_versions(id, project_id FK, version, run_id FK, change_type ENUM(MAJOR,MINOR,PATCH),
             changelog TEXT, created_at, UNIQUE(project_id, version))
```

**Indexes:** `generation_runs(project_id, created_at DESC)`, `generation_runs(status)`, `sdk_versions(project_id, created_at DESC)`, `generation_run_logs(run_id, seq)`.

**Retention:** run logs are kept 90 days, artifacts 30 days. A nightly scheduled job in `api-app` (ShedLock, so it runs once across replicas) deletes older ones. Run rows themselves are kept.

---

## 5. Generation pipeline (`worker-app`)

### 5.1 Steps

Each step implements `PipelineStep { StepName name(); StepResult execute(RunContext ctx); }`. `RunContext` holds the run, project, config, a `VcsClient`, a `RunLogger` and a cancellation check. After each step the worker saves `current_step` and the step's output (snapshot id, diff, version) to the run row.

**Idempotency:** a step whose output is already saved for this run is skipped. This lets a redelivered message, or a *resume* after approval, continue where it stopped.

| # | Step | Behavior | Failure codes |
|---|---|---|---|
| 1 | **FetchSpec** | Validate `owner/repo`. Fetch `spec_path` at the head of `default_branch` and record `commit_sha`. Reject files over 2 MB. | `SPEC_NOT_FOUND`, `SPEC_TOO_LARGE`, `VCS_AUTH` |
| 2 | **ParseSpec** | swagger-parser (OpenAPI 2.0/3.0/3.1, YAML or JSON) → `ApiModel` (operations, schemas, security, servers, tags). Merge path-level and operation-level params; the operation-level one wins on `(name, in)`. Upsert `spec_snapshots` by content hash. | `SPEC_INVALID` |
| 3 | **LintSpec** 🆕 | Run the lint rules (§5.3). Save to `lint_results`. Fail the run if `lint_fail_on` is reached. | `LINT_FAILED` |
| 4 | **DiffSpec** | Compare with the snapshot of the last **SUCCESS, non-dry-run** run (§5.4). | — |
| 5 | **CalcVersion** | §5.5. | — |
| 6 | **BreakingGate** 🆕 | If the diff has breaking changes: `ALLOW` continues; `BLOCK` fails the run; `REQUIRE_APPROVAL` sets status `AWAITING_APPROVAL`, creates a `run_approvals` row, acks the message and stops. When approved, the API re-enqueues `{runId}` and the pipeline resumes at step 7. When rejected, the run is set to `CANCELLED`. | `BREAKING_BLOCKED` |
| 7 | **RunGenerators** | Run each selected generator in parallel (virtual threads). Each returns `List<GeneratedFile>`. If one generator fails, the run fails, and the log names the generator. | `GENERATOR_ERROR` |
| 8 | **SyntaxCheck** | Write the files to a tarball, start a sandbox container (§5.6), run the checks and formatters, read back the formatted files. | `CHECK_FAILED`, `SANDBOX_TIMEOUT` |
| 9 | **PackageArtifact** 🆕 | Zip the output and upload to S3 as `artifacts/{orgId}/{runId}.zip`. Build the manifest by comparing each file with the target branch (ADDED/MODIFIED/UNCHANGED/SKIPPED). | `STORAGE_ERROR` |
| 10 | **CommitOutput** | Skipped when `dry_run`. Target is the same repo (APPEND) or `output_repo_full_name` (SEPARATE). Create branch `emitkit/run-{runId}`. Commit all files with one Git tree. Skip any path under `*/custom/*` that already exists on the base branch. Add the publish workflows (§6.7). Open the PR (title `EmitKit: SDK v{version}`, body = changelog + lint summary). If breaking, post the breaking-change comment 🆕 63. | `VCS_CONFLICT`, `VCS_AUTH` |
| 11 | **Finalize** | Set status `SUCCESS`. Insert `sdk_versions` only if the version changed and the run is not a dry run. Publish `[DONE]`. | — |

**Cancellation:** checked before each step and inside long steps (generators, sandbox wait). A cancelled run deletes its remote branch if one was created, then sets `CANCELLED`.

**Retry:** these exceptions count as transient: `VcsRateLimitException`, `VcsServerException` (5xx), `SandboxInfraException`, `StorageException`. They go through the retry queues described in the overview §3. Any other exception fails the run immediately.

### 5.2 `ApiModel` (spec-engine)

A language-neutral, immutable record tree that every generator uses:

- `ApiModel(info, servers, securitySchemes, tags, operations, schemas)`
- `Operation(operationId, method, path, tag, summary, description, parameters, requestBody, responses, security, deprecated)`
- `Parameter(name, in, required, schema, description)`
- `Schema` is a sealed interface: `ObjectSchema | ArraySchema | PrimitiveSchema | EnumSchema | RefSchema | OneOfSchema | AllOfSchema`.

If an operation has no `operationId`, one is made from `{method}{PathInPascalCase}` (e.g. `getUsersById`) and a lint warning is raised. Names are normalized for each language by `NameStrategy` implementations (camelCase, snake_case, PascalCase), with a list of reserved words per language.

### 5.3 Lint rules (🆕 24)

| Rule | Severity |
|---|---|
| `operation-operationId` (missing) | WARNING |
| `operation-operationId-unique` | ERROR |
| `operation-description` (missing summary and description) | WARNING |
| `operation-tags` (no tag) | WARNING |
| `path-params-defined` (template var not declared) | ERROR |
| `no-unused-components` | INFO |
| `info-contact`, `info-description` | INFO |
| `operationId-casing` (not camelCase) | WARNING |
| `server-url-https` | WARNING |

The rule set is fixed in v1; rule customization is not in scope. The frontend runs the same rules via an API call (`POST /api/v1/lint`) so the editor shows inline markers.

### 5.4 Diff (🆕 25 — richer than today)

Operations are matched by `operationId`. The `SpecDiff` output holds:

- `added`, `removed`: operation lists.
- `modified`: per operation, a list of `Change(kind, location, breaking)`.

**Breaking changes:**
- operation removed
- new required parameter, or an optional parameter becomes required
- parameter removed
- request body becomes required
- a required property added to a request schema
- a response property removed
- an enum value removed
- a type changed

**Non-breaking changes:**
- operation added
- optional parameter added
- response property added
- description or summary changed

The diff is saved in `generation_runs.diff_summary`. The spec diff viewer also calls the differ directly to compare any two snapshots (`GET /api/v1/projects/{id}/spec/diff?from=&to=`).

### 5.5 Versioning

- `SPEC_VERSION`: use `info.version`, defaulting to `0.1.0` if it is not valid SemVer.
- `EMITKIT_MANAGED` (same rules as the current app):
  - First run → `0.1.0`.
  - Breaking change or removed operation → **major** (**minor** while below 1.0.0).
  - Added or modified operation → **minor** (**patch** while below 1.0.0).
  - No change → the version stays the same and no `sdk_versions` row is created.

Implemented with `com.github.zafarkhaja:java-semver`.

### 5.6 Sandbox (syntax check & format)

- Image `emitkit/toolchain:{ver}`: Node 20 (typescript, prettier), Python 3.12 (ruff), Temurin JDK 21 (javac, google-java-format).
- The worker runs each check in a new container through `docker-java`, behind a `SandboxRunner` interface (v4 adds a Kubernetes Job implementation).
- **gVisor runtime:** every sandbox container runs with gVisor (`runsc`) instead of the default `runc` runtime. gVisor puts its own user-space kernel between the container and the host, so generated code never makes system calls to the host kernel directly. In docker-java this is `HostConfig.withRuntime("runsc")`; all other settings below stay the same.
  - Setting `emitkit.sandbox.runtime`: `runsc` (default) or `runc`. `runc` is allowed only with the `dev` or `test` profiles, for machines without gVisor installed.
  - At startup the worker checks `docker info` for the `runsc` runtime. With the `prod` or `staging` profile, the worker refuses to start if it's missing.
  - gVisor's platform is `systrap` (the default; works on VMs without nested virtualization).
- **Separate Docker daemon:** sandbox containers run on a dedicated **rootless** Docker daemon, not the host's main daemon. The worker gets only that daemon's socket, so even an escape from gVisor lands in an unprivileged user account, not root on the host.
  - gVisor + rootless Docker is a less common combination. Milestone 6 starts with a short spike to confirm it works with the limits below (cgroup v2 delegation for `--cpus`/`--memory`/`--pids-limit`). If it doesn't, the fallback is gVisor on a dedicated **rootful** sandbox daemon, still separate from the main daemon; gVisor then is the main isolation boundary.
- Container settings:
  - `--network none` (no network at all);
  - `--read-only` root filesystem, plus a `tmpfs` workspace at `/work` (512 MB);
  - `--cpus 1 --memory 1g --pids-limit 256`;
  - `--cap-drop ALL --security-opt no-new-privileges`, default seccomp profile, non-root `--user 10001`;
  - hard timeout of 180 s; the worker kills the container if it runs longer;
  - removed right after it finishes (`--rm`, plus a cleanup sweep every 10 min for leftovers labelled `emitkit.sandbox=true`).
- Input and output: the worker copies the tarball into the container (`copyArchiveToContainer`), runs `check.sh`, and copies the result tarball and `report.json` back out (`copyArchiveFromContainer`). Nothing goes through object storage.
- Concurrency: a semaphore per worker limits sandbox containers to `emitkit.sandbox.max-concurrent` (default 4).
- Checks per language:
  - TS: `tsc --noEmit --strict` and `prettier --write`.
  - Python: `ruff check --fix` and `ruff format`.
  - Java: `javac` (against generated stubs plus vendored jars of declared dependencies) and `google-java-format`.
- Local dev uses the same `DockerSandboxRunner`. It can point at the normal Docker daemon (`emitkit.sandbox.docker-host`) and use `runc` if gVisor isn't installed, which is fine on a dev machine.
- Tests:
  - An integration test (runs in CI with gVisor installed) confirms the container really runs under gVisor: `dmesg` inside the container shows gVisor's boot messages.
  - Escape-attempt tests: no network (`curl` fails), can't write outside `/work`, runs as uid 10001, gets killed at the timeout, can't fork past the pids limit.
  - The toolchain image gets a CI job that runs the full check suite under `runsc`, since a few tools behave differently there (file watching, some `/proc` files). `check.sh` turns off file watchers and runs tools in single-run mode.

---

## 6. Generators

All generators implement:

```java
public interface Generator {
  GeneratorId id();                       // TS_SDK, PY_SDK, JAVA_SDK, CLI, MCP, DOCS
  List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx);
}
record GeneratedFile(String path, String content, FileKind kind /* GENERATED | CUSTOM_STUB */) {}
```

`GeneratorContext` carries the output dir, version, package names and an optional `DocsAiAssistant` (used by docs only; implemented by the `ai` module with Spring AI). Templates are Mustache (`com.samskivert:jmustache`) and live under `src/main/resources/templates/{generator}/`. Output must be byte-stable for the same input, which the golden tests check.

| Gen | Output dir | Contents |
|---|---|---|
| **TS SDK** | `{out}/sdk/typescript/` | `src/client.ts` (fetch-based, base URL, auth headers from securitySchemes, retries), `src/resources/{tag}.ts`, `src/types.ts`, `src/errors.ts` (`ApiError`, `NotFoundError`…), `package.json` (`@{scope}/{name}`), `tsconfig.json`, `README.md`, `src/custom/index.ts` stub |
| **Python SDK** | `{out}/sdk/python/` | `{pkg}/client.py` (httpx sync + async), `{pkg}/models.py` (Pydantic v2), `{pkg}/resources/{tag}.py`, `{pkg}/errors.py`, `pyproject.toml`, `README.md`, `{pkg}/custom/__init__.py` stub |
| **Java SDK** 🆕 | `{out}/sdk/java/` | `build.gradle.kts`, `src/main/java/{group}/{Api}Client.java` (java.net.http + Jackson), `model/*` (records), `resources/{Tag}Api.java`, `ApiException` hierarchy, builder-based config, `custom/` stub |
| **CLI** | `{out}/cli/` | Commander ^11 TS CLI, one subcommand per operation (`{tag} {op}`), flags from params, `--json` body input, `bin` in `package.json` |
| **MCP** | `{out}/mcp/` | `@modelcontextprotocol/sdk` server. Each operation is a tool, with `inputSchema` derived from params and body. Stdio transport. `package.json` |
| **Docs** | `{out}/docs/v{version}/` | `index.md` plus `{tag}.md`. Each operation has its method, path, params table, request/response schemas and examples (curl, TS, Python, 🆕 Java). Examples are made from templates. If an AI key is set, `DocsAiAssistant` (Spring AI `ChatClient`, §6.1) adds richer examples and prose; AI failures fall back to the template output silently, with a log warning |
| **Workflows** | `.github/workflows/` | `emitkit-publish-npm.yml`, `emitkit-publish-pypi.yml`, 🆕 `emitkit-publish-maven.yml`. Triggered on tags `sdk/v*`, using repo secrets `NPM_TOKEN`, `PYPI_TOKEN`, `MAVEN_*` |

### 6.1 AI with Spring AI (docs enrichment)

- Library: **Spring AI** with its Google GenAI (Gemini) chat model module. v1 uses AI only for docs.
- **Bring your own key:** each project has its own encrypted key, so the chat model can't be one shared Spring bean. Auto-configured chat model beans are switched off (`spring.ai.model.chat=none`).
- A `ChatModelFactory` in the `ai` module builds a `ChatClient` for each run, from `ai_provider` + `ai_model` + the decrypted key. The client is thrown away when the step ends, so the key isn't kept in memory.
- `ai_model` is optional; if it's empty, the factory uses a default from `application.yml` (`emitkit.ai.gemini.default-model`), so no model name is hard-coded in Java.
- Structured output: `ChatClient.prompt()...call().entity(DocsExamples.class)`, a record with `curl`, `typescript`, `python`, `java` and `notes`. If the response can't be mapped, the step uses the template output.
- Timeout 30 s per call, at most 1 retry, calls batched by tag (one call per tag, not per operation).
- Tests use a fake `ChatModel` that returns recorded responses; no real AI calls in CI.

---

## 7. VCS layer (🆕 70)

```java
public interface VcsProvider {
  VcsProviderId id();                                   // GITHUB in v1
  VcsClient forUser(String accessToken);
  VcsClient forProject(Project project);                // uses creator's token
  WebhookVerifier webhookVerifier();
}
public interface VcsClient {
  List<VcsOrg> listOrgs(); List<VcsRepo> listRepos();
  VcsRepo createRepo(String org, String name, boolean isPrivate);
  FileContent getFile(String repo, String path, String ref);          // content + sha
  CommitRef putFile(String repo, String path, String content, String expectedSha, String message);
  void createBranch(String repo, String name, String fromSha);
  CommitRef commitTree(String repo, String branch, List<GeneratedFile> files, String message);
  PullRequest openPr(String repo, String head, String base, String title, String body);
  void comment(String repo, int prNumber, String body);
  void createTag(String repo, String tag, String sha);
  Webhook registerWebhook(String repo, String url, String secret, Set<String> events);
  void deleteWebhook(String repo, long id);
  RateLimit rateLimit();
}
```

The GitHub implementation uses `org.kohsuke:github-api` with an OkHttp connector and response cache.

**Rate limits (🆕 75):**
- The `X-RateLimit-Remaining` / `X-RateLimit-Reset` headers of every response are recorded in Redis per token, as `ratelimit:{userId}`.
- When remaining is below 100, calls from background work (workers) wait until reset, up to 5 min, then throw `VcsRateLimitException`, which is retried. Calls from user requests fail fast with `code = VCS_RATE_LIMITED` and the reset time.
- `GET /api/v1/me/vcs-quota` exposes the quota to the UI header.

The project's token is the OAuth token of `projects.created_by`. If that user loses access, runs fail with `VCS_AUTH` and the UI asks an admin to re-link the project. GitHub App installation tokens are a v2 candidate, not in scope.

---

## 8. REST API (`/api/v1`)

All endpoints except auth, webhooks and health need a session. Org-scoped endpoints check permissions as described in §3.

| Method & path | Permission | Notes |
|---|---|---|
| `GET /me` | — | user and orgs with roles |
| `GET /me/vcs-quota` | — | rate-limit info |
| `POST /me/sync-orgs` | — | re-sync GitHub orgs on demand |
| `GET /orgs` · `GET /orgs/{orgId}` | ORG_VIEW | includes memberCount, isPersonal |
| `GET /orgs/{orgId}/members` · `PATCH /orgs/{orgId}/members/{userId}` | ORG_VIEW · MEMBER_ROLE_EDIT | |
| `GET /orgs/{orgId}/projects` | PROJECT_VIEW | |
| `GET /orgs/{orgId}/vcs-repos` | PROJECT_CREATE | repo picker |
| `GET /orgs/{orgId}/vcs-repos/{owner}/{repo}/spec-check?path=&branch=` | PROJECT_CREATE | new-project flow: checks the file exists and parses; returns title and version |
| `POST /orgs/{orgId}/projects` | PROJECT_CREATE | `{mode: EXISTING|NEW, repo, specPath, defaultBranch, visibility?, outputMode, outputRepo?}` |
| `POST /projects/{id}/webhook` | CONFIG_EDIT | re-register the webhook (new secret) when it's missing or failing |
| `GET /projects/{id}` · `DELETE /projects/{id}` | PROJECT_VIEW · PROJECT_DELETE | delete removes the webhook (best effort, logs a warning on failure) |
| `GET /projects/{id}/config` · `PUT /projects/{id}/config` | PROJECT_VIEW · CONFIG_EDIT | PUT creates a new config version. The AI key is write-only; GET returns `aiKeySet: true` |
| `GET /projects/{id}/spec` | SPEC_VIEW | `{content, sha, format}` |
| `PUT /projects/{id}/spec` | SPEC_EDIT | `{content, sha, message?, generate?: bool}`. A sha mismatch returns 409 `SPEC_CONFLICT` |
| `GET /projects/{id}/spec/snapshots` · `GET /projects/{id}/spec/diff?from&to` | SPEC_VIEW | diff viewer |
| `POST /lint` | — | `{content}` → lint results (editor markers) |
| `GET /projects/{id}/runs` · `POST /projects/{id}/runs` | RUN_VIEW · RUN_TRIGGER | POST `{dryRun?: bool}` |
| `GET /runs/{runId}` | RUN_VIEW | includes diff_summary, lint counts, approval |
| `POST /runs/{runId}/cancel` | RUN_CANCEL | only for QUEUED, RUNNING or AWAITING_APPROVAL |
| `POST /runs/{runId}/rerun` | RUN_RERUN | new run with the same config version and parent_run_id. It re-fetches the spec at the current head |
| `POST /runs/{runId}/approval` | RUN_APPROVE | `{decision, comment}` |
| `GET /runs/{runId}/logs` · `GET /runs/{runId}/logs/stream` | RUN_VIEW | the stream is SSE |
| `GET /runs/{runId}/files` · `GET /runs/{runId}/files/{path}?diff=true` | RUN_VIEW | preview: manifest, file content, unified diff against the base branch |
| `GET /runs/{runId}/artifact` | RUN_VIEW | the API streams the zip from RustFS (`Content-Disposition: attachment`). RustFS isn't reachable from the internet, so no presigned URL |
| `GET /runs/{runId}/lint` | RUN_VIEW | |
| `GET /projects/{id}/versions` | RUN_VIEW | |
| `POST /webhooks/github` | HMAC | see §9. Returns 503 if Vault is unreachable, so GitHub redelivers |
| `GET /actuator/health`, `GET /v3/api-docs`, `/swagger-ui` | public | |

**Spec save with `generate=true`:** the commit and the run are created in one request. If the commit succeeds and enqueueing fails, the response is 202, includes `runError`, and the UI shows a retry button.

---

## 9. Webhooks

- `POST /webhooks/github`, using its own filter chain with no session and no CSRF.
- Read the raw body. Look up projects by `repository.full_name` among projects in any org. Verify `X-Hub-Signature-256` against each candidate's decrypted secret with a constant-time comparison. If none match → 401.
- Deliveries are de-duplicated by `X-GitHub-Delivery`, stored in Redis for 24 h.
- `push`: if `ref == refs/heads/{default_branch}` and the spec path is among the added/modified/removed files of the commits → create a WEBHOOK run and enqueue it. Otherwise → 200 with an "ignored" reason.
- `pull_request` with `action=closed`, `merged=true`, head ref `emitkit/run-{runId}`:
  - Create tag `sdk/v{version}` at `merge_commit_sha`.
  - If MCP is among the outputs, also create `mcp/v{version}`.
  - If the tag already exists, log it and return 200.
- Respond within 10 s. Tagging is done synchronously; it's two API calls.

---

## 10. Frontend (React)

Built fresh to the rules in overview §4 (design system, URLs, code architecture, states, accessibility). No code is copied from `apps/web`.

### 10.1 Routes
| Route | Contents |
|---|---|
| `/login` | GitHub sign-in; returns to `?next=` after login |
| `/` | redirects to the last-used org (kept in `localStorage`; falls back to the personal org) |
| `/orgs/$orgId` | **Projects**: searchable, filterable project list (status of last run, current version, repo). "New project" button if permitted. Empty state leads to the new-project flow |
| `/orgs/$orgId/members` 🆕 | member list with role (admins can change roles inline); role source shown (GitHub sync / manual) |
| `/orgs/$orgId/settings` | org info (slug, GitHub ID, member count), "Re-sync from GitHub" |
| `/orgs/$orgId/projects/new` | 3-step guided flow (§10.2) |
| `/projects/$id` | **Overview** (§10.3) |
| `/projects/$id/runs` | run list: status, trigger, version, duration, PR. Filters (`?status=&trigger=`) in the URL. "Trigger run" and "Dry run" buttons |
| `/projects/$id/editor` | spec editor (§10.4) |
| `/projects/$id/diff` 🆕 | spec diff viewer: pick two snapshots (`?from=&to=`), summary of added/removed/changed operations with breaking ones first, then a side-by-side text diff |
| `/projects/$id/config` | config form (§10.5) |
| `/projects/$id/versions` | version history: version, change type, date, run, expandable changelog |
| `/projects/$id/settings` | webhook status with "Re-register", output mode, danger zone (delete) |
| `/runs/$runId` | run page (§10.6); child routes `/logs` (default), `/files` 🆕, `/lint` 🆕, `/changes` 🆕 |

### 10.2 New-project flow
1. **Repository:** choose *Existing repo* (searchable picker with owner, visibility, last pushed) or *New repo* (name, visibility; name checked as you type).
2. **Spec:** spec path (for existing repos, the file is checked as you type; a found file shows its title and version) and default branch (pre-filled).
3. **Output:** output mode (same repo / separate repo with its own picker) and a summary of what EmitKit will do (register a webhook, create a starter spec for new repos).

Progress is kept in search params, so Back works and a refresh keeps the step. Finishing opens the project's Config page with a "Choose what to generate" prompt.

### 10.3 Project overview
Answers "what's the state of this project?" at a glance:
- **Last run:** status, trigger, time, duration, link; Trigger run / Rerun buttons.
- **Current version** and change type, plus link to the open EmitKit PR if any.
- **Spec:** path, branch, last commit; lint counts from the last run.
- **Health:** webhook OK / failing, GitHub quota, "AI key set" if relevant.
- **Recent activity:** the last 5 runs.

### 10.4 Spec editor
- CodeMirror with YAML/JSON modes, line numbers, folding, search.
- Debounced (500 ms) swagger-parser validation in the browser and lint markers from `POST /lint`, shown inline and in a **Problems** panel (click a problem to jump to the line).
- Toolbar: Save (`⌘S`) · Save & Generate · Dry run; shows "Unsaved changes" and blocks leaving the page with unsaved edits.
- On 409 `SPEC_CONFLICT`: a dialog with **Reload** (discard mine) or **Compare**, which opens a merge view of their version and mine before saving again.

### 10.5 Config form
- Sections: Outputs · SDK languages · Package names · Versioning · Breaking-change policy · Lint policy · AI.
- Fields appear only when relevant (languages only if SDK is selected; npm / PyPI / Maven fields per language).
- The AI key shows "Key set" with a **Replace** button; it is never sent back to the browser.
- Save is disabled until something changes; leaving with unsaved changes asks first. Read-only users see the values without inputs.

### 10.6 Run page
- **Header:** status pill, trigger, branch, short SHA (copy button), version, duration, PR link; actions Cancel / Rerun / Download zip (shown by status and permission).
- **Step timeline:** the 11 pipeline steps as a horizontal (desktop) or vertical (mobile) timeline with each step's status and duration. Clicking a step filters the logs to that step.
- **Logs:** live over SSE, virtualized, auto-scroll that pauses when you scroll up ("Jump to latest" button), filter by step and level, search, copy, download as text.
- **Files 🆕:** tree with ADDED / MODIFIED / UNCHANGED / SKIPPED badges; a diff view for a selected file (`?path=`).
- **Lint 🆕:** table by severity with rule, location and message.
- **Changes 🆕:** the diff summary, breaking changes first.
- **Approval banner** when `AWAITING_APPROVAL`: lists the breaking changes; admins see Approve / Reject with a comment field.

---

## 11. Non-functional requirements

| Area | Target |
|---|---|
| API latency | p95 < 300 ms for non-VCS endpoints |
| Run duration | p95 < 3 min for a 200-operation spec with all outputs |
| Throughput | 4 concurrent sandbox checks per worker container (configurable); 8 per host with 2 workers |
| Availability | 99% monthly for the API (single Compose host; higher targets in v4) |
| Security | OWASP ASVS L2 for auth, session and access control. Dependency scanning (OWASP dependency-check, `npm audit`) in CI |
| Backups | Nightly `pg_dump` + WAL archiving to off-host storage, 7-day retention. Vault Raft snapshots every 6 h, stored off-host (without the Transit key, the stored secrets can't be read). RustFS bucket mirrored nightly |
| Vault | Transit p95 < 20 ms; same host as the apps, so the same availability |

---

## 12. Parity checklist (exit gate)

- [ ] GitHub login, org sync, personal org, org switcher
- [ ] Create project from existing repo, and with a new repo + starter spec
- [ ] Webhook registered on create and deleted on project delete
- [ ] Config: outputs, languages, output dir, npm scope, PyPI name, version strategy, AI key
- [ ] Spec editor: load, validate, save with conflict detection, save & generate
- [ ] Manual run and webhook run (push to the default branch touching the spec)
- [ ] Live logs, run list, run detail
- [ ] TS SDK, Python SDK, CLI, MCP, docs (with and without AI) produced and checked
- [ ] APPEND and SEPARATE modes. Custom stubs are not overwritten
- [ ] PR opened. Merge creates `sdk/v*` (and `mcp/v*`) tags
- [ ] npm and PyPI workflow files generated
- [ ] Version history with change type
- [ ] Secrets encrypted at rest. CORS allowlist enforced
- [ ] Every v1 🆕 feature has at least one end-to-end test

---

## 13. Implementation milestones (inside v1)

Backend and frontend tracks run in parallel. Each frontend milestone needs only the API endpoints of the backend milestone listed next to it; until those exist, it works against MSW mocks built from the OpenAPI document.

**Backend track**
1. **Foundations:** Gradle modules, docker-compose (including Vault dev mode with the Transit key bootstrapped by an init script), Flyway V1, `SecretCipher` with Vault Transit + Testcontainers Vault tests, Vault AppRole roles and policies, CI, ArchUnit, `docker-compose.prod.yml` with Caddy.
2. **Auth & orgs:** OAuth login, sessions, org sync, roles, `OrgAccessService`.
3. **Projects & VCS:** `VcsProvider`/GitHub, project create/delete, webhooks, rate-limit tracking.
4. **Spec engine:** parser, `ApiModel`, linter, differ, semver (pure Java, test-first).
5. **Generators:** TS → Python → Java → CLI → MCP → docs → workflows, each with golden tests.
6. **Worker pipeline:** gVisor + rootless Docker spike, RabbitMQ topology, steps 1–11, Docker sandbox runner (gVisor, rootless daemon), cancel, retry, gate.
7. **Run endpoints:** SSE logs, files/preview, lint, approvals, zip download.
8. **Hardening & cutover:** backups and restore drill, load test, security review, parity checklist, delete TS code.

**Frontend track**
- **F1. Design system** (with backend 1): Vite app scaffold, `tokens.css`, `ui/` components, Storybook with visual tests, lint rules (no raw palette classes, import boundaries).
- **F2. App shell & auth** (with backend 2): router and layouts, login, `/me`, CSRF and 401 handling, org switcher, sidebar, command palette, members page, error and empty-state patterns.
- **F3. Projects** (with backend 3): projects list, new-project flow, project overview, settings, versions.
- **F4. Spec editor & diff** (with backend 4): editor with validation, lint markers and conflict handling; diff viewer.
- **F5. Config** (with backend 5): config form for all outputs and languages.
- **F6. Runs** (with backends 6–7): run list, run page with timeline, live logs, files, lint, changes, approvals.
- **F7. Polish & audit** (with backend 8): accessibility audit, mobile pass, performance budget, Playwright E2E for the parity checklist.

Each milestone gets its own implementation plan.
