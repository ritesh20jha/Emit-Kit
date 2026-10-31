# EmitKit Rebuild — v2: Scale & Ecosystem

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Depends on:** [Overview](2026-10-31-emitkit-rebuild-overview-design.md), [v1](2026-10-31-emitkit-rebuild-v1-design.md) (shipped)

---

## 1. Goal & exit criteria

v2 turns EmitKit from a single-user GitHub tool into a team product with a wider ecosystem:
- team features (API tokens, invites, audit log);
- more inputs (multiple specs, specs from a URL, schedules, PR previews);
- more outputs (languages, Postman, mock server, hooks, docs site, tests);
- direct publishing to package registries;
- notifications and analytics.

**Exit criteria**
1. Each feature ships behind a feature flag (overview §5) and is enabled in production.
2. Every feature has end-to-end coverage. Every new generator has golden tests and compile tests.
3. No v1 regression: the v1 Playwright suite and the parity checklist still pass.
4. Flyway migrations run without downtime (expand → migrate → contract).

---

## 2. Scope

| Group | Features |
|---|---|
| Auth & access | 4 API tokens |
| Orgs & teams | 10 invites outside the GitHub org · 11 audit log |
| Projects | 19 multiple specs per project · 20 spec from URL |
| Config | 28 SDK languages: Go, C#, Kotlin · 33 custom template overrides |
| Runs | 42 scheduled runs · 43 PR-branch preview generation |
| Generators | 51 Go/C#/Kotlin SDKs · 55 Postman · 56 mock server · 57 React Query hooks · 58 docs site → GitHub Pages · 59 generated SDK tests |
| Releases | 64 direct publishing (npm, PyPI, Maven Central) |
| Notifications | 71 email/Slack/Discord · 72 in-app feed |
| Analytics | 73 run analytics dashboard · 74 queue/worker health page |

**Not in v2:** AI assistant, AI release notes and migration guides, GitLab/Bitbucket (all v3).

---

## 3. Multiple specs per project (19) & spec from URL (20)

This is the largest data-model change in v2, so it's built first.

### Data model
```
spec_sources(id, project_id FK, name, slug,           -- e.g. "billing-api"
             kind ENUM(REPO_FILE, URL),
             spec_path NULL,                          -- REPO_FILE
             url NULL, url_auth_header_enc NULL,      -- URL (optional bearer/basic header)
             package_suffix NULL,                     -- appended to npm/pypi/maven names
             created_at, UNIQUE(project_id, slug))
```
- Migration: create one `spec_sources` row per project from `projects.spec_path` with slug `default`. Add `spec_source_id` to `generation_runs`, `spec_snapshots` and `sdk_versions`, and backfill it. Drop `projects.spec_path` in a later release (contract step).
- Output paths become `{out}/{slug}/sdk/typescript/...`. The `default` slug keeps the v1 layout (no slug segment), so existing users see no change.
- Versions are tracked **per spec source**. Tags become `{slug}/sdk/v{x}`; the default source keeps `sdk/v{x}`. Run branches stay `emitkit/run-{runId}`, and each run covers exactly one source.
- Webhook push: create a run for each REPO_FILE source whose path changed.

### Spec from URL
- Only for sources with `kind=URL`. The FetchSpec step does an HTTP GET with a 10 s timeout, a 2 MB limit and an SSRF guard: resolve DNS first and reject private, loopback, link-local and metadata ranges; allow `https` only, plus `http` in dev.
- URL sources can't be edited in the editor (it's read-only for them). Runs start manually or on a schedule (§5).
- Change detection: if the content hash equals the last successful snapshot, the run ends as `SUCCESS` with "no changes", without commit or PR.

### UI
The project page gets a spec source selector. The Editor, Diff, Runs and Versions tabs are filtered by the selected source. Settings has a "Spec sources" list to add, edit and remove sources (remove needs a confirmation that shows how many runs and versions belong to the source).

---

## 4. Team features

### 4.1 API tokens (4)
```
api_tokens(id, org_id FK, name, token_hash, prefix CHAR(8), scopes JSONB,
           created_by FK, last_used_at, expires_at NULL, revoked_at NULL)
```
- Format: `ek_{prefix}_{random32}`. Shown once. Stored as a SHA-256 hash.
- Scopes, as a subset of v1 permissions: `runs:trigger`, `runs:read`, `specs:read`, `specs:write`, `versions:read`.
- Auth: `Authorization: Bearer ek_...` goes through its own security filter. Token requests skip CSRF and act as a service principal scoped to the token's org.
- Admins manage tokens at `/orgs/$orgId/settings/tokens`.
- Rate limit: 60 requests/min per token (Redis token bucket, via bucket4j).

### 4.2 Invites (10)
```
org_invites(id, org_id FK, email, role, token_hash, invited_by FK,
            expires_at, accepted_at NULL, accepted_by FK NULL)
```
- An admin invites by email and role. The email contains `/invite/{token}`, valid 7 days.
- The invitee must still sign in with GitHub (that is still the only identity provider). Accepting creates `org_members` with `role_source=MANUAL`.
- Members added by invite are kept when GitHub org sync runs; sync only adds or updates `SYNC` members.
- Removing a member: admin only. Members with `role_source=SYNC` come back on the next sync if they are still in the GitHub org, and the UI says so.
- Email is sent through the notification service (§7).

### 4.3 Audit log (11)
```
audit_events(id, org_id FK, actor_type ENUM(USER,TOKEN,SYSTEM,WEBHOOK), actor_id,
             action, target_type, target_id, metadata JSONB, ip, user_agent, created_at)
```
- Written by a Spring `@Audited` aspect on service methods, inside the same transaction.
- Actions: `project.create|delete`, `config.update` (metadata holds the changed field *names*, never values), `spec.update`, `run.trigger|cancel|rerun|approve|reject`, `member.role_change|invite|remove`, `token.create|revoke`, `publish.credentials_update`, `notification.channel_update`.
- UI: `/orgs/$orgId/audit` with filters for actor, action, target and date range; CSV export (admin).
- Retention 1 year. Rows cannot be changed or deleted through the app; the DB role has no UPDATE/DELETE on this table.

---

## 5. Runs

### 5.0 Run model changes
- `generation_runs.trigger` gains `SCHEDULE` and `PR_PREVIEW`.
- New nullable columns: `generation_runs.source_ref` (the commit SHA the spec was fetched at; defaults to the default-branch head) and `generation_runs.preview_pr_number`.
- `VcsClient` gains `listComments(repo, prNumber)`, `updateComment(repo, commentId, body)` and `setCommitStatus(repo, sha, context, state, description, targetUrl)`.

### 5.1 Scheduled runs (42)
```
run_schedules(id, spec_source_id FK, cron, timezone, enabled, skip_if_unchanged BOOL DEFAULT true,
              last_fired_at, next_fire_at, created_by FK)
```
- A scheduler in `api-app` (ShedLock, every 30 s) finds due schedules with `SELECT ... FOR UPDATE SKIP LOCKED`, creates a run with `trigger=SCHEDULE`, and works out `next_fire_at` (Spring `CronExpression`).
- Minimum interval is 1 h. With `skip_if_unchanged`, the run finishes early as described in §3.
- UI: a Schedule panel on the Runs tab with a cron builder, presets (hourly, daily, weekly) and a preview of the next 5 fire times.

### 5.2 PR-branch preview generation (43)
- New project setting `pr_previews: OFF | ON`. The webhook also subscribes to `pull_request` actions `opened`, `synchronize` and `reopened`.
- If the PR's changed files include a REPO_FILE spec path, create a run with `trigger=PR_PREVIEW`, `dry_run=true`, `source_ref=headSha`, `preview_pr_number=prNumber`.
- The pipeline fetches the spec at the head SHA and diffs against the default-branch baseline. It never commits.
- It posts or updates **one** PR comment, found by a hidden marker (`<!-- emitkit:preview -->`). The comment contains the version bump, the breaking-change list, lint counts, a table of changed files per output, and a link to the run preview page.
- It also sets a commit status (`emitkit/preview`), which fails if there are breaking changes and the gate is `BLOCK`.
- PRs from forks are ignored (no secrets, no trust).

---

## 6. Generators & publishing

### 6.1 New SDK languages (28, 51)
All use the v1 `Generator` SPI and the shared `ApiModel`, and each adds a `NameStrategy` with its reserved words. The toolchain image gains Go 1.23, .NET 8 SDK and Kotlin 2.x.

| Gen | Output dir | Contents | Check / format |
|---|---|---|---|
| Go | `{out}/sdk/go/` | `go.mod` (module path from config), `client.go` (net/http, context, functional options), `models.go`, `{tag}_service.go`, `errors.go`, `custom/` | `go vet`, `gofmt` |
| C# | `{out}/sdk/csharp/` | `.csproj` (net8.0), `{Api}Client.cs` (HttpClient + System.Text.Json), `Models/*.cs` (records), `Resources/*Api.cs`, `ApiException`, `Custom/` | `dotnet build`, `dotnet format` |
| Kotlin | `{out}/sdk/kotlin/` | `build.gradle.kts`, client (Ktor client + kotlinx.serialization), `model/` (data classes), `resources/`, suspend functions, `custom/` | `kotlinc`, `ktlint --format` |

New config fields: `go_module_path`, `nuget_package_id`, `kotlin_group_id`/`kotlin_artifact_id`.

### 6.2 Other outputs (55–59)
`outputs` gains `POSTMAN`, `MOCK`, `REACT_QUERY` and `DOCS_SITE`.

| Output | Output dir | Contents |
|---|---|---|
| **Postman** (55) | `{out}/postman/collection.json` | Postman Collection v2.1: folders per tag, a request per operation with example bodies, an environment file with `baseUrl` and auth variables |
| **Mock server** (56) | `{out}/mock/` | Small Node (Hono) server. Returns examples from the spec, or values synthesized from schemas with a fixed seed, so output is repeatable. Validates requests against the spec. Includes a `Dockerfile` |
| **React Query hooks** (57) | `{out}/sdk/typescript-react-query/` | Builds on the TS SDK, which is required (validated at config save). `use{Op}Query` for GET, `use{Op}Mutation` for others, plus query-key factories per tag |
| **Docs site** (58) | `{out}/docs-site/` | Static Scalar/Redoc page plus the markdown docs. Adds `.github/workflows/emitkit-docs-pages.yml`, which deploys to GitHub Pages on `sdk/v*` tags. Project setting `docs_site_base_path` |
| **Generated tests** (59) | in each SDK | Per-language tests that check request building (method, path, query, headers, body) against a recorded fake transport. No network. Toggle `generate_tests` (default on). The sandbox also *runs* them (`vitest`, `pytest`, `gradle test`, `go test`, `dotnet test`) and fails the run on failure |

### 6.3 Custom template overrides (33)
- Overrides live **in the user's repo** at `.emitkit/templates/{generator}/{template}.mustache`. They are versioned with their code and need no upload UI.
- The FetchSpec step also fetches that directory (limits: 200 files, 1 MB total).
- The template resolver tries the override first, then the built-in template. Every template documents its context variables in `templates/{generator}/README.md`, which is published in EmitKit's own docs.
- Safety: Mustache is logic-less (no code execution). Rendering has a 5 s timeout and a 10 MB output limit per template.
- The UI's Config tab lists the active overrides it detected (read-only).
- A **template contract version** is shown in each generator's README. Overrides that declare an older contract produce a lint warning.

### 6.4 Direct publishing (64)
```
publish_credentials(id, org_id FK, registry ENUM(NPM,PYPI,MAVEN_CENTRAL), name,
                    secret_enc, metadata JSONB,            -- e.g. maven namespace, gpg key id
                    created_by FK, created_at, rotated_at)
project_publish_targets(id, project_id FK, spec_source_id FK, generator ENUM, credential_id FK,
                        mode ENUM(ON_MERGE, MANUAL), enabled)
publish_jobs(id, run_id FK, target_id FK, status, registry_url, log_ref, created_at, finished_at)
```
- Credentials:
  - npm: automation token.
  - PyPI: API token.
  - Maven Central: Central Portal token plus a GPG private key and passphrase, all encrypted.
- Trigger:
  - `ON_MERGE`: when the PR merge webhook creates the tag, enqueue a `publish.jobs` message for each enabled target.
  - `MANUAL`: a "Publish" button on the Versions tab.
- Execution: a sandbox Job that, unlike the check sandbox, **is allowed egress only to the registry hosts**. It builds from the tagged commit and publishes with the credentials, which are mounted as a short-lived Kubernetes Secret that is deleted right after.
- Idempotency: a "version already exists" response from the registry → `SKIPPED`, not failed.
- Mutual exclusion: a project can either have the v1 CI workflow files **or** direct publishing for a given generator. Turning on direct publishing stops those workflow files from being emitted.

---

## 7. Notifications (71, 72)

```
notification_channels(id, org_id FK, kind ENUM(EMAIL,SLACK,DISCORD), name,
                      target_enc,        -- email list, or webhook URL
                      created_by FK, enabled)
notification_rules(id, org_id FK, project_id FK NULL,  -- NULL = all projects
                   event ENUM(RUN_FAILED, RUN_SUCCEEDED, PR_OPENED, RELEASE_TAGGED,
                              APPROVAL_REQUIRED, PUBLISH_FAILED, PUBLISH_SUCCEEDED),
                   channel_id FK)
notifications(id, user_id FK, org_id FK, event, title, body, link, read_at NULL, created_at)
```
- Domain events (`RunFinished`, `ApprovalRequested`…) are written to an **outbox** table inside the business transaction. An outbox relay sends them to the RabbitMQ exchange `emitkit.events`. A `notification-dispatcher` consumer in `worker-app` matches rules and sends.
- Senders:
  - Email: Spring Mail via SMTP, with Thymeleaf templates.
  - Slack: incoming webhook with Block Kit.
  - Discord: webhook with an embed.
- Each send is retried 3× with backoff. Failures are logged and shown on the channel ("last error").
- In-app feed: each event also creates a `notifications` row for every org member with `RUN_VIEW` on the project, except `APPROVAL_REQUIRED`, which goes to admins only. The header shows a bell with the unread count, updated over an SSE stream (`/api/v1/me/notifications/stream`). Rows are kept 30 days.
- Each user can mute email per project (`notification_mutes(user_id, project_id)`).
- UI: `/orgs/$orgId/settings/notifications` to manage channels (with a "Send test" button) and rules.

---

## 8. Analytics & ops (73, 74)

### Run analytics dashboard (73)
- `/orgs/$orgId/analytics` with a project filter and a date range (7/30/90 days).
- Charts: runs per day stacked by status, success rate, p50/p95 duration, duration per step (stacked), top failure codes, breaking changes over time, versions released.
- Data: a nightly rollup into `run_daily_stats(org_id, project_id, day, status_counts JSONB, p50_ms, p95_ms, step_p95 JSONB)`, plus a live query for today. Per-step timing comes from new columns in `generation_run_steps(run_id, step, started_at, finished_at, status)`, which the worker writes as of v2.

### Queue & worker health (74)
- `/admin/health`, visible only to **platform operators**, a new `users.is_platform_admin` flag set by hand, not an org role.
- Shows:
  - RabbitMQ queue depth, consumer count and DLQ size (management HTTP API);
  - active worker instances (heartbeat in Redis every 10 s, key TTL 30 s) and in-flight runs per worker;
  - sandbox Jobs running and pending;
  - the GitHub rate-limit status of the most active tokens.
- DLQ actions: list messages, requeue one, discard one. Each action is audited (with `actor_type=USER` and org = platform).

---

## 9. API additions (`/api/v1`)

| Method & path | Permission |
|---|---|
| `GET/POST /projects/{id}/spec-sources` · `PATCH/DELETE /spec-sources/{sid}` | PROJECT_VIEW / CONFIG_EDIT |
| existing spec, run and version endpoints gain `?specSource={sid}` (defaults to the `default` source) | as in v1 |
| `GET/POST /orgs/{orgId}/tokens` · `DELETE /tokens/{tid}` | MEMBER_ROLE_EDIT (admin) |
| `GET/POST /orgs/{orgId}/invites` · `DELETE /invites/{iid}` · `POST /invites/{token}/accept` | admin / authenticated |
| `DELETE /orgs/{orgId}/members/{userId}` | admin |
| `GET /orgs/{orgId}/audit` (+ `?format=csv`) | admin |
| `GET/PUT /spec-sources/{sid}/schedule` | CONFIG_EDIT |
| `GET/POST /orgs/{orgId}/publish-credentials` · `DELETE …/{cid}` | admin |
| `GET/PUT /projects/{id}/publish-targets` · `POST /versions/{vid}/publish` · `GET /publish-jobs/{jid}` | CONFIG_EDIT / RUN_TRIGGER / RUN_VIEW |
| `GET/POST /orgs/{orgId}/notification-channels` · `POST …/{cid}/test` · `GET/PUT /orgs/{orgId}/notification-rules` | admin |
| `GET /me/notifications` · `POST /me/notifications/read` · `GET /me/notifications/stream` | — |
| `GET /orgs/{orgId}/analytics?projectId&from&to` | ORG_VIEW |
| `GET /admin/health` · `GET/POST /admin/dlq…` | platform admin |

The v1 permission table gains `PUBLISH_MANAGE` (admin) and `ANALYTICS_VIEW` (all roles).

---

## 10. Testing

- Golden tests and compile tests for Go, C#, Kotlin, Postman (validated against the collection JSON schema), mock server (boot it and run smoke requests), React Query, and docs site (HTML snapshot).
- Generated SDK tests must pass inside the sandbox for the full set of fixture specs.
- SSRF guard: a unit test for each blocked range, plus a DNS-rebinding test (resolve once, then connect by IP).
- Publishing: Testcontainers Verdaccio (npm) and pypiserver. Maven Central is mocked with WireMock. Credentials are verified to never appear in logs (log-capture assertion).
- Webhook PR preview: WireMock GitHub. Checks that a single comment is created and then updated in place.
- Notifications: GreenMail (SMTP) and a WireMock Slack/Discord endpoint. Outbox relay tested under a crash (kill after commit, before publish).
- Migrations: `spec_sources` backfill tested on a copy of production-sized data in staging.

---

## 11. Milestones

1. **Multi-spec data model and spec from URL** (with the migration).
2. **Team features:** API tokens, invites, audit log.
3. **Notifications:** outbox, dispatcher, channels, in-app feed.
4. **Scheduled runs and PR previews.**
5. **New SDK languages:** Go, C#, Kotlin, plus toolchain image v2.
6. **Other outputs:** Postman, mock, React Query, docs site, generated tests.
7. **Custom template overrides.**
8. **Direct publishing.**
9. **Analytics and the ops health page.**

Each milestone gets its own implementation plan and ships behind its flag.
