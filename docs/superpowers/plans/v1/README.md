# EmitKit v1 — Implementation Plan (index)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. **One ticket file = one PR.** Pick any ticket whose dependencies are merged, follow its tasks in order, open the PR.

**Goal:** Deliver EmitKit v1 (parity + core additions) on Spring Boot 4 + React as 97 small PRs that can be built in parallel.

**Architecture:** Gradle modular monolith (`api-app`, `worker-app` + shared modules) and a fresh React SPA. Early **interface tickets** (module interfaces + fakes) and **contract tickets** (REST DTOs + 501 stubs → OpenAPI → generated TS client) unblock parallel work across lanes.

**Tech Stack:** Java 21, Spring Boot 4, Gradle (Kotlin DSL), PostgreSQL 16 + Flyway, RabbitMQ, Redis, Vault Transit, RustFS, Spring AI (Gemini), github-api, swagger-parser, JMustache, docker-java + gVisor; React 19, Vite, pnpm, TanStack Router/Query, Tailwind v4, shadcn/ui, CodeMirror 6.

**Spec:** [v1 design](../../specs/2026-10-31-emitkit-rebuild-v1-design.md) and [overview](../../specs/2026-10-31-emitkit-rebuild-overview-design.md). Refs like `v1 §5.1` / `ov §4.5` point there.

**Conventions used in every ticket file**
- Java root package `dev.emitkit`; modules → packages: `common`→`dev.emitkit.common`, `domain`→`dev.emitkit.domain`, `vcs`→`dev.emitkit.vcs`, `spec-engine`→`dev.emitkit.spec`, `generators/*`→`dev.emitkit.gen.*`, `ai`→`dev.emitkit.ai`, `api-app`→`dev.emitkit.api`, `worker-app`→`dev.emitkit.worker`.
- Backend commands run from `backend/` (`./gradlew :module:test --tests 'Class'`); frontend commands from `frontend/` (`pnpm vitest run path`, `pnpm playwright test`).
- Tests: JUnit 5 + AssertJ; integration tests end in `IT` and use Testcontainers; frontend uses Vitest + Testing Library + MSW.
- Commits: Conventional Commits, one per task (`feat(spec): …`, `test(vcs): …`).
- **Reading test stubs:** a test shown with only a name (`@Test void cancelQueued_isCancelledImmediately() { }`) is pinned by its name plus the ticket's **Interfaces** block — write the assertion the name states, with the values given there. Tests with bodies show the exact values to assert.
- **Spec refinements made while planning** (already applied to the v1 spec): `step_outputs` column on `generation_runs`, project soft delete (`V6`), ShedLock (`V5`), `VcsClient.commitTree` takes `CommitFile`, `forProject(userId)`, `createTag` returns `boolean`; `ErrorCode` adds `RATE_LIMITED` and `NOT_IMPLEMENTED`.

## Global Constraints

- **PR size:** ≤ 1000 changed lines (added + deleted) per PR. Excluded from the count: lockfiles (`*.lock`, `pnpm-lock.yaml`, `gradle/libs.versions.toml` is *included*), the generated client `frontend/src/lib/api/generated/**`, the exported `frontend/openapi.json`, and golden snapshot outputs under `**/src/test/resources/golden/**` and generated fixture inputs under `**/golden-inputs/**`. Enforced by `scripts/pr-size.sh` in CI (ticket T02). Only **CUT-01** (deleting the TS code) may carry the `size-exempt` label. If a ticket grows past the limit, split it before review, not after.
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

## Dependency waves

A ticket's wave is the length of its longest dependency chain, computed from the **Depends on** line in each ticket file. A ticket can start as soon as its own dependencies are merged; it doesn't need to wait for the whole previous wave.

| Wave | Tickets (all can run in parallel once earlier waves they depend on are merged) |
|---|---|
| 0 | [T01](T01-backend-skeleton.md), [T03](T03-local-stack.md), [T04](T04-frontend-scaffold.md), [SBX-01](SBX-01-gvisor-spike.md), [SBX-02](SBX-02-check-script.md) |
| 1 | [OBJ-01](OBJ-01-artifact-storage.md), [T02](T02-ci-pr-size.md), [CORE-01](CORE-01-common-errors-ids.md), [SPEC-01](SPEC-01-api-model.md), [UI-01](UI-01-tokens-atoms.md) |
| 2 | [API-00](API-00-api-skeleton.md), [CORE-02](CORE-02-secret-cipher.md), [DB-01](DB-01-identity-schema.md), [WRK-00](WRK-00-worker-skeleton.md), [SPEC-02](SPEC-02-parser-operations.md), [GEN-01](GEN-01-generator-spi.md), [VCS-01](VCS-01-interfaces.md), [SBX-03](SBX-03-docker-sandbox-runner.md), [UI-02](UI-02-form-controls.md), [UI-03](UI-03-overlays.md), [UI-04](UI-04-data-display.md) |
| 3 | [AI-01](AI-01-docs-ai-assistant.md), [DB-02](DB-02-projects-schema.md), [SPEC-03](SPEC-03-parser-schemas.md), [SPEC-04](SPEC-04-linter.md), [GEN-11](GEN-11-workflows.md), [VCS-02](VCS-02-github-files.md), [CON-01](CON-01-contract-me-orgs.md), [UI-05](UI-05-command-palette.md), [OPS-01](OPS-01-prod-compose.md) |
| 4 | [DB-03](DB-03-runs-schema.md), [SPEC-05](SPEC-05-differ-operations.md), [GEN-00](GEN-00-fixture-specs.md), [VCS-03](VCS-03-github-git-data.md), [VCS-04](VCS-04-webhooks-hmac.md), [VCS-05](VCS-05-rate-limits.md), [AUTH-01](AUTH-01-github-login.md), [CON-02](CON-02-contract-projects.md), [CON-03](CON-03-contract-runs.md), [WEB-01](WEB-01-api-auth-layer.md), [OPS-02](OPS-02-backups.md) |
| 5 | [DB-04](DB-04-run-results-schema.md), [SPEC-06](SPEC-06-differ-schemas-semver.md), [GEN-02](GEN-02-ts-sdk-client.md), [GEN-04](GEN-04-py-sdk-client.md), [GEN-06](GEN-06-java-sdk-client.md), [GEN-08](GEN-08-cli.md), [GEN-09](GEN-09-mcp.md), [GEN-10](GEN-10-docs.md), [AUTH-02](AUTH-02-org-sync.md), [AUTH-03](AUTH-03-org-access.md), [WEB-02](WEB-02-app-shell.md), [WEB-12](WEB-12-live-logs.md) |
| 6 | [GEN-03](GEN-03-ts-sdk-types.md), [GEN-05](GEN-05-py-sdk-models.md), [GEN-07](GEN-07-java-sdk-models.md), [OPS-03](OPS-03-retention-rewrap.md), [PRJ-01](PRJ-01-create-existing.md), [PRJ-06](PRJ-06-lint-diff-endpoints.md), [RUN-01](RUN-01-runs-create-list.md), [WRK-01](WRK-01-pipeline-core.md), [WEB-03](WEB-03-palette-wiring.md), [WEB-04](WEB-04-members-settings.md), [WEB-05](WEB-05-projects-list.md) |
| 7 | [GEN-12](GEN-12-toolchain-compile-tests.md), [HOOK-01](HOOK-01-webhook-core.md), [PRJ-02](PRJ-02-create-new-repo.md), [PRJ-03](PRJ-03-project-crud.md), [PRJ-04](PRJ-04-config.md), [PRJ-05](PRJ-05-spec-get-put.md), [RUN-02](RUN-02-cancel-rerun-approve.md), [RUN-04](RUN-04-files-artifacts.md), [WRK-02](WRK-02-run-logger.md), [WRK-03](WRK-03-fetch-parse.md), [WRK-06](WRK-06-run-generators.md), [WRK-07](WRK-07-syntax-check.md), [WRK-08](WRK-08-package-artifact.md), [WEB-06](WEB-06-new-project.md), [WEB-07](WEB-07-project-overview.md) |
| 8 | [HOOK-02](HOOK-02-push-merge.md), [RUN-03](RUN-03-logs-sse.md), [RUN-05](RUN-05-save-and-generate.md), [WRK-04](WRK-04-lint-diff-version.md), [WRK-09](WRK-09-commit-output.md), [WEB-08](WEB-08-spec-editor.md), [WEB-10](WEB-10-config-form.md), [WEB-11](WEB-11-runs-list.md) |
| 9 | [WRK-05](WRK-05-breaking-gate.md), [WRK-10](WRK-10-finalize.md), [WEB-09](WEB-09-conflict-diff.md), [WEB-13](WEB-13-run-page.md) |
| 10 | [WRK-11](WRK-11-retry-cancel.md), [WEB-14](WEB-14-a11y-perf.md) |
| 11 | [WRK-12](WRK-12-pipeline-e2e.md) |
| 12 | [WEB-15](WEB-15-e2e-parity.md), [OPS-04](OPS-04-load-security.md) |
| 13 | [CUT-01](CUT-01-delete-ts.md) |

**Lanes:** `PLAT` platform/infra · `CORE` shared backend · `SPEC` spec-engine · `GEN` generators · `VCS` GitHub · `API` api-app features · `WRK` worker · `SBX` sandbox · `UI` design system · `WEB` frontend pages · `OPS` production & cutover.

---

## Tickets

| Ticket | Title | Lane | Depends on | Size | Wave |
|---|---|---|---|---|---|
| [T01](T01-backend-skeleton.md) | Backend monorepo skeleton | PLAT | — | 450 | 0 |
| [T03](T03-local-stack.md) | Local dev stack (docker-compose) | PLAT | — | 350 | 0 |
| [T04](T04-frontend-scaffold.md) | Frontend scaffold | PLAT | — | 600 | 0 |
| [SBX-01](SBX-01-gvisor-spike.md) | Spike: gVisor + rootless Docker | SBX | — | 150 | 0 |
| [SBX-02](SBX-02-check-script.md) | Toolchain check.sh contract | SBX | — | 200 | 0 |
| [OBJ-01](OBJ-01-artifact-storage.md) | Artifact storage client (RustFS / S3) | PLAT | [T01](T01-backend-skeleton.md), [T03](T03-local-stack.md) | 400 | 1 |
| [T02](T02-ci-pr-size.md) | CI pipeline + PR size gate | PLAT | [T01](T01-backend-skeleton.md), [T04](T04-frontend-scaffold.md) | 300 | 1 |
| [CORE-01](CORE-01-common-errors-ids.md) | Common: errors, IDs, problem-details types | CORE | [T01](T01-backend-skeleton.md) | 350 | 1 |
| [SPEC-01](SPEC-01-api-model.md) | ApiModel + naming | SPEC | [T01](T01-backend-skeleton.md) | 600 | 1 |
| [UI-01](UI-01-tokens-atoms.md) | Tokens, theme, Storybook, base atoms | UI | [T04](T04-frontend-scaffold.md) | 700 | 1 |
| [API-00](API-00-api-skeleton.md) | api-app skeleton | CORE | [CORE-01](CORE-01-common-errors-ids.md) | 600 | 2 |
| [CORE-02](CORE-02-secret-cipher.md) | SecretCipher (Vault Transit + local AES) | CORE | [CORE-01](CORE-01-common-errors-ids.md), [T03](T03-local-stack.md) | 600 | 2 |
| [DB-01](DB-01-identity-schema.md) | Migration V1: identity + entities | CORE | [T01](T01-backend-skeleton.md), [CORE-01](CORE-01-common-errors-ids.md) | 600 | 2 |
| [WRK-00](WRK-00-worker-skeleton.md) | worker-app skeleton + RabbitMQ topology | CORE | [CORE-01](CORE-01-common-errors-ids.md) | 500 | 2 |
| [SPEC-02](SPEC-02-parser-operations.md) | Parser: operations & parameters | SPEC | [SPEC-01](SPEC-01-api-model.md) | 700 | 2 |
| [GEN-01](GEN-01-generator-spi.md) | Generator SPI + template engine + golden harness | GEN | [SPEC-01](SPEC-01-api-model.md) | 500 | 2 |
| [VCS-01](VCS-01-interfaces.md) | VCS interfaces + in-memory fake | VCS | [CORE-01](CORE-01-common-errors-ids.md) | 550 | 2 |
| [SBX-03](SBX-03-docker-sandbox-runner.md) | DockerSandboxRunner | SBX | [SBX-01](SBX-01-gvisor-spike.md), [SBX-02](SBX-02-check-script.md), [CORE-01](CORE-01-common-errors-ids.md) | 750 | 2 |
| [UI-02](UI-02-form-controls.md) | Form controls | UI | [UI-01](UI-01-tokens-atoms.md) | 650 | 2 |
| [UI-03](UI-03-overlays.md) | Overlays & feedback | UI | [UI-01](UI-01-tokens-atoms.md) | 600 | 2 |
| [UI-04](UI-04-data-display.md) | Data display & states | UI | [UI-01](UI-01-tokens-atoms.md) | 750 | 2 |
| [AI-01](AI-01-docs-ai-assistant.md) | Spring AI docs assistant | CORE | [GEN-01](GEN-01-generator-spi.md), [CORE-02](CORE-02-secret-cipher.md) | 550 | 3 |
| [DB-02](DB-02-projects-schema.md) | Migration V2: projects + configs | CORE | [DB-01](DB-01-identity-schema.md) | 500 | 3 |
| [SPEC-03](SPEC-03-parser-schemas.md) | Parser: schemas | SPEC | [SPEC-02](SPEC-02-parser-operations.md) | 600 | 3 |
| [SPEC-04](SPEC-04-linter.md) | Linter | SPEC | [SPEC-02](SPEC-02-parser-operations.md) | 600 | 3 |
| [GEN-11](GEN-11-workflows.md) | Workflows generator | GEN | [GEN-01](GEN-01-generator-spi.md) | 300 | 3 |
| [VCS-02](VCS-02-github-files.md) | GitHub client: identity, repos, files | VCS | [VCS-01](VCS-01-interfaces.md) | 750 | 3 |
| [CON-01](CON-01-contract-me-orgs.md) | Contract: me, orgs, members | API | [API-00](API-00-api-skeleton.md), [T04](T04-frontend-scaffold.md) | 500 | 3 |
| [UI-05](UI-05-command-palette.md) | Command palette | UI | [UI-03](UI-03-overlays.md) | 450 | 3 |
| [OPS-01](OPS-01-prod-compose.md) | Production images + compose + Caddy | OPS | [API-00](API-00-api-skeleton.md), [WRK-00](WRK-00-worker-skeleton.md), [T04](T04-frontend-scaffold.md), [SBX-01](SBX-01-gvisor-spike.md) | 650 | 3 |
| [DB-03](DB-03-runs-schema.md) | Migration V3: runs, snapshots, logs | CORE | [DB-02](DB-02-projects-schema.md) | 600 | 4 |
| [SPEC-05](SPEC-05-differ-operations.md) | Differ: operations & parameters | SPEC | [SPEC-03](SPEC-03-parser-schemas.md) | 650 | 4 |
| [GEN-00](GEN-00-fixture-specs.md) | Shared fixture specs | GEN | [SPEC-03](SPEC-03-parser-schemas.md) | 700 | 4 |
| [VCS-03](VCS-03-github-git-data.md) | GitHub client: git data, PRs, tags | VCS | [VCS-02](VCS-02-github-files.md) | 700 | 4 |
| [VCS-04](VCS-04-webhooks-hmac.md) | Webhooks register/delete + HMAC verifier | VCS | [VCS-02](VCS-02-github-files.md) | 400 | 4 |
| [VCS-05](VCS-05-rate-limits.md) | Rate-limit tracking | VCS | [VCS-02](VCS-02-github-files.md) | 500 | 4 |
| [AUTH-01](AUTH-01-github-login.md) | GitHub OAuth login + /me | API | [CON-01](CON-01-contract-me-orgs.md), [DB-01](DB-01-identity-schema.md), [CORE-02](CORE-02-secret-cipher.md) | 700 | 4 |
| [CON-02](CON-02-contract-projects.md) | Contract: projects, config, spec, lint | API | [CON-01](CON-01-contract-me-orgs.md) | 650 | 4 |
| [CON-03](CON-03-contract-runs.md) | Contract: runs, logs, files, artifact, versions | API | [CON-01](CON-01-contract-me-orgs.md) | 600 | 4 |
| [WEB-01](WEB-01-api-auth-layer.md) | API & auth layer | WEB | [T04](T04-frontend-scaffold.md), [CON-01](CON-01-contract-me-orgs.md) | 550 | 4 |
| [OPS-02](OPS-02-backups.md) | Backups + restore drill | OPS | [OPS-01](OPS-01-prod-compose.md) | 450 | 4 |
| [DB-04](DB-04-run-results-schema.md) | Migration V4: run results | CORE | [DB-03](DB-03-runs-schema.md) | 450 | 5 |
| [SPEC-06](SPEC-06-differ-schemas-semver.md) | Differ: schema changes + SemVer | SPEC | [SPEC-05](SPEC-05-differ-operations.md) | 600 | 5 |
| [GEN-02](GEN-02-ts-sdk-client.md) | TS SDK: client, errors, package | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 600 | 5 |
| [GEN-04](GEN-04-py-sdk-client.md) | Python SDK: client, errors, pyproject | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 600 | 5 |
| [GEN-06](GEN-06-java-sdk-client.md) | Java SDK: build, client, exceptions | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 650 | 5 |
| [GEN-08](GEN-08-cli.md) | CLI generator | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 600 | 5 |
| [GEN-09](GEN-09-mcp.md) | MCP server generator | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 600 | 5 |
| [GEN-10](GEN-10-docs.md) | Docs generator | GEN | [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) | 700 | 5 |
| [AUTH-02](AUTH-02-org-sync.md) | Org sync | API | [AUTH-01](AUTH-01-github-login.md), [VCS-02](VCS-02-github-files.md) | 600 | 5 |
| [AUTH-03](AUTH-03-org-access.md) | OrgAccessService + orgs & members endpoints | API | [AUTH-01](AUTH-01-github-login.md) | 650 | 5 |
| [WEB-02](WEB-02-app-shell.md) | App shell + login | WEB | [WEB-01](WEB-01-api-auth-layer.md), [UI-03](UI-03-overlays.md), [UI-04](UI-04-data-display.md) | 800 | 5 |
| [WEB-12](WEB-12-live-logs.md) | Live logs (useEventStream + LogView) | WEB | [WEB-01](WEB-01-api-auth-layer.md), [CON-03](CON-03-contract-runs.md) | 700 | 5 |
| [GEN-03](GEN-03-ts-sdk-types.md) | TS SDK: types + resources | GEN | [GEN-02](GEN-02-ts-sdk-client.md) | 700 | 6 |
| [GEN-05](GEN-05-py-sdk-models.md) | Python SDK: Pydantic models + resources | GEN | [GEN-04](GEN-04-py-sdk-client.md) | 700 | 6 |
| [GEN-07](GEN-07-java-sdk-models.md) | Java SDK: models (records) + resources | GEN | [GEN-06](GEN-06-java-sdk-client.md) | 750 | 6 |
| [OPS-03](OPS-03-retention-rewrap.md) | Retention + key re-wrap jobs | API | [DB-04](DB-04-run-results-schema.md), [OBJ-01](OBJ-01-artifact-storage.md), [CORE-02](CORE-02-secret-cipher.md) | 450 | 6 |
| [PRJ-01](PRJ-01-create-existing.md) | Create project (existing repo) + repo picker + spec-check | API | [AUTH-03](AUTH-03-org-access.md), [VCS-04](VCS-04-webhooks-hmac.md), [DB-02](DB-02-projects-schema.md), [CON-02](CON-02-contract-projects.md) | 800 | 6 |
| [PRJ-06](PRJ-06-lint-diff-endpoints.md) | Lint endpoint, snapshots, spec diff | API | [SPEC-04](SPEC-04-linter.md), [SPEC-06](SPEC-06-differ-schemas-semver.md), [DB-03](DB-03-runs-schema.md), [CON-02](CON-02-contract-projects.md) | 450 | 6 |
| [RUN-01](RUN-01-runs-create-list.md) | Create/list/get runs + enqueue | API | [AUTH-03](AUTH-03-org-access.md), [DB-04](DB-04-run-results-schema.md), [WRK-00](WRK-00-worker-skeleton.md), [CON-03](CON-03-contract-runs.md) | 650 | 6 |
| [WRK-01](WRK-01-pipeline-core.md) | Pipeline core | WRK | [WRK-00](WRK-00-worker-skeleton.md), [DB-04](DB-04-run-results-schema.md) | 700 | 6 |
| [WEB-03](WEB-03-palette-wiring.md) | Command palette wiring + shortcuts | WEB | [WEB-02](WEB-02-app-shell.md), [UI-05](UI-05-command-palette.md) | 350 | 6 |
| [WEB-04](WEB-04-members-settings.md) | Members + org settings | WEB | [WEB-02](WEB-02-app-shell.md), [CON-01](CON-01-contract-me-orgs.md) | 500 | 6 |
| [WEB-05](WEB-05-projects-list.md) | Projects list | WEB | [WEB-02](WEB-02-app-shell.md), [CON-02](CON-02-contract-projects.md) | 450 | 6 |
| [GEN-12](GEN-12-toolchain-compile-tests.md) | Toolchain image + compile tests | GEN | [GEN-03](GEN-03-ts-sdk-types.md), [GEN-05](GEN-05-py-sdk-models.md), [GEN-07](GEN-07-java-sdk-models.md), [GEN-08](GEN-08-cli.md), [GEN-09](GEN-09-mcp.md), [SBX-02](SBX-02-check-script.md) | 500 | 7 |
| [HOOK-01](HOOK-01-webhook-core.md) | Webhook endpoint core | API | [VCS-04](VCS-04-webhooks-hmac.md), [PRJ-01](PRJ-01-create-existing.md), [CORE-02](CORE-02-secret-cipher.md) | 550 | 7 |
| [PRJ-02](PRJ-02-create-new-repo.md) | Create project (new repo) | API | [PRJ-01](PRJ-01-create-existing.md) | 350 | 7 |
| [PRJ-03](PRJ-03-project-crud.md) | Project get/list/delete + webhook re-register | API | [PRJ-01](PRJ-01-create-existing.md) | 450 | 7 |
| [PRJ-04](PRJ-04-config.md) | Config get/put | API | [PRJ-01](PRJ-01-create-existing.md) | 550 | 7 |
| [PRJ-05](PRJ-05-spec-get-put.md) | Spec get/put | API | [PRJ-01](PRJ-01-create-existing.md) | 400 | 7 |
| [RUN-02](RUN-02-cancel-rerun-approve.md) | Cancel, rerun, approval endpoints | API | [RUN-01](RUN-01-runs-create-list.md) | 550 | 7 |
| [RUN-04](RUN-04-files-artifacts.md) | Files preview, artifact download, lint results, versions | API | [RUN-01](RUN-01-runs-create-list.md), [OBJ-01](OBJ-01-artifact-storage.md) | 700 | 7 |
| [WRK-02](WRK-02-run-logger.md) | RunLogger | WRK | [WRK-01](WRK-01-pipeline-core.md) | 450 | 7 |
| [WRK-03](WRK-03-fetch-parse.md) | Steps: FetchSpec + ParseSpec | WRK | [WRK-01](WRK-01-pipeline-core.md), [VCS-05](VCS-05-rate-limits.md), [SPEC-03](SPEC-03-parser-schemas.md) | 550 | 7 |
| [WRK-06](WRK-06-run-generators.md) | Step: RunGenerators | WRK | [WRK-01](WRK-01-pipeline-core.md), [GEN-01](GEN-01-generator-spi.md), [AI-01](AI-01-docs-ai-assistant.md) | 400 | 7 |
| [WRK-07](WRK-07-syntax-check.md) | Step: SyntaxCheck | WRK | [WRK-01](WRK-01-pipeline-core.md), [SBX-03](SBX-03-docker-sandbox-runner.md) | 400 | 7 |
| [WRK-08](WRK-08-package-artifact.md) | Step: PackageArtifact | WRK | [WRK-01](WRK-01-pipeline-core.md), [OBJ-01](OBJ-01-artifact-storage.md), [VCS-03](VCS-03-github-git-data.md) | 500 | 7 |
| [WEB-06](WEB-06-new-project.md) | New-project flow | WEB | [WEB-05](WEB-05-projects-list.md), [UI-02](UI-02-form-controls.md) | 800 | 7 |
| [WEB-07](WEB-07-project-overview.md) | Project overview, settings, versions | WEB | [WEB-05](WEB-05-projects-list.md), [CON-03](CON-03-contract-runs.md) | 800 | 7 |
| [HOOK-02](HOOK-02-push-merge.md) | Push trigger + merge tagging | API | [HOOK-01](HOOK-01-webhook-core.md), [RUN-01](RUN-01-runs-create-list.md), [VCS-03](VCS-03-github-git-data.md) | 500 | 8 |
| [RUN-03](RUN-03-logs-sse.md) | Logs + SSE stream | API | [RUN-01](RUN-01-runs-create-list.md), [WRK-02](WRK-02-run-logger.md) | 550 | 8 |
| [RUN-05](RUN-05-save-and-generate.md) | Save spec with generate=true | API | [PRJ-05](PRJ-05-spec-get-put.md), [RUN-01](RUN-01-runs-create-list.md) | 250 | 8 |
| [WRK-04](WRK-04-lint-diff-version.md) | Steps: LintSpec + DiffSpec + CalcVersion | WRK | [WRK-03](WRK-03-fetch-parse.md), [SPEC-04](SPEC-04-linter.md), [SPEC-06](SPEC-06-differ-schemas-semver.md) | 500 | 8 |
| [WRK-09](WRK-09-commit-output.md) | Step: CommitOutput | WRK | [WRK-08](WRK-08-package-artifact.md), [GEN-11](GEN-11-workflows.md) | 650 | 8 |
| [WEB-08](WEB-08-spec-editor.md) | Spec editor | WEB | [WEB-07](WEB-07-project-overview.md), [UI-02](UI-02-form-controls.md) | 850 | 8 |
| [WEB-10](WEB-10-config-form.md) | Config form | WEB | [WEB-07](WEB-07-project-overview.md), [UI-02](UI-02-form-controls.md) | 800 | 8 |
| [WEB-11](WEB-11-runs-list.md) | Runs list | WEB | [WEB-07](WEB-07-project-overview.md), [CON-03](CON-03-contract-runs.md) | 450 | 8 |
| [WRK-05](WRK-05-breaking-gate.md) | Step: BreakingGate | WRK | [WRK-04](WRK-04-lint-diff-version.md) | 400 | 9 |
| [WRK-10](WRK-10-finalize.md) | Step: Finalize | WRK | [WRK-09](WRK-09-commit-output.md) | 250 | 9 |
| [WEB-09](WEB-09-conflict-diff.md) | Conflict merge + spec diff page | WEB | [WEB-08](WEB-08-spec-editor.md) | 650 | 9 |
| [WEB-13](WEB-13-run-page.md) | Run page: header, timeline, tabs, approval | WEB | [WEB-12](WEB-12-live-logs.md), [WEB-11](WEB-11-runs-list.md) | 850 | 9 |
| [WRK-11](WRK-11-retry-cancel.md) | Retry, DLQ, cancellation, idempotency | WRK | [WRK-10](WRK-10-finalize.md), [RUN-02](RUN-02-cancel-rerun-approve.md) | 650 | 10 |
| [WEB-14](WEB-14-a11y-perf.md) | Accessibility, mobile, performance pass | WEB | [WEB-13](WEB-13-run-page.md), [WEB-10](WEB-10-config-form.md), [WEB-09](WEB-09-conflict-diff.md), [WEB-04](WEB-04-members-settings.md), [WEB-06](WEB-06-new-project.md), [WEB-03](WEB-03-palette-wiring.md) | 400 | 10 |
| [WRK-12](WRK-12-pipeline-e2e.md) | Pipeline end-to-end test | WRK | [WRK-11](WRK-11-retry-cancel.md), [GEN-12](GEN-12-toolchain-compile-tests.md), [HOOK-02](HOOK-02-push-merge.md) | 500 | 11 |
| [WEB-15](WEB-15-e2e-parity.md) | E2E parity suite | WEB | [WEB-14](WEB-14-a11y-perf.md), [WRK-12](WRK-12-pipeline-e2e.md) | 800 | 12 |
| [OPS-04](OPS-04-load-security.md) | Load test + security checks | OPS | [WRK-12](WRK-12-pipeline-e2e.md), [OPS-01](OPS-01-prod-compose.md) | 400 | 12 |
| [CUT-01](CUT-01-delete-ts.md) | Delete the TypeScript code (size-exempt) | OPS | [WEB-15](WEB-15-e2e-parity.md), [WRK-12](WRK-12-pipeline-e2e.md), [OPS-02](OPS-02-backups.md), [OPS-04](OPS-04-load-security.md) | (deletions; size-exempt) | 13 |
