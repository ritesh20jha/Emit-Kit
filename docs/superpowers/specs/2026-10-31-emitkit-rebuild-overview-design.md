# EmitKit Rebuild — Overview & Shared Architecture

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Phase specs:**
- [v1 — Parity + core additions](2026-10-31-emitkit-rebuild-v1-design.md)
- [v2 — Scale & ecosystem](2026-10-31-emitkit-rebuild-v2-design.md)
- [v3 — AI & multi-VCS](2026-10-31-emitkit-rebuild-v3-design.md)

---

## 1. Goal

Rebuild EmitKit from scratch on **Java 21 + Spring Boot 3.x** (backend) and **React 19** (frontend) as a **production multi-tenant SaaS**. The rebuild keeps every feature of the current Bun/TypeScript app and adds the new features agreed for v1–v3.

EmitKit connects to a user's GitHub repositories, watches an OpenAPI spec, and generates SDKs, CLIs, MCP servers and docs. It opens pull requests with the generated code and tags releases when those PRs are merged.

### Decisions

| Topic | Decision |
|---|---|
| Purpose | Production SaaS (real users, multiple orgs) |
| Repo | Same repo. The TS code is deleted once v1 passes its parity checklist |
| Backend shape | Gradle modular monolith with two deployables: `api-app` and `worker-app` |
| Database | PostgreSQL 16, migrations with Flyway |
| Queue | RabbitMQ (jobs, retries, dead-letter queues) |
| Cache / pub-sub | Redis (live log fan-out, Spring Session, rate-limit counters) |
| Object storage | S3-compatible (MinIO for local dev) for run artifacts |
| Generators | Our own templates: swagger-parser → internal `ApiModel` → Mustache templates per target |
| Deployment | Docker images, Kubernetes, Helm chart. Syntax checks run in short-lived sandboxed Kubernetes Jobs |
| AI | Pluggable `AiProvider` interface. Gemini is the default; the user supplies the key |

### Out of scope (all phases)
- Billing, subscription plans, usage-based pricing.
- Proxying or routing live API traffic (EmitKit is not an API gateway).
- Visual drag-and-drop schema designer.
- Native mobile apps.

---

## 2. Repository layout (end of v1)

```
/backend                       Gradle multi-module (Kotlin DSL), Java 21
  settings.gradle.kts
  common/                      error types, Result helpers, AES-256-GCM crypto, ids (ULID)
  domain/                      JPA entities, Spring Data repositories, Flyway migrations
  vcs/                         VcsProvider interface + github/ implementation
  spec-engine/                 parser, ApiModel, differ, semver calculator, linter
  generators/
    core/                      Generator SPI, template engine wrapper, GeneratedFile model
    typescript-sdk/
    python-sdk/
    java-sdk/
    cli/
    mcp/
    docs/
    workflows/                 GitHub Actions publish workflows
  ai/                          AiProvider interface + gemini/ implementation
  api-app/                     Spring Boot app: REST, security, webhooks, SSE
  worker-app/                  Spring Boot app: RabbitMQ consumers, pipeline, sandbox runner
/frontend                      React 19 + Vite + TS
/deploy
  docker/                      Dockerfiles (api, worker, web, toolchain)
  helm/emitkit/                Helm chart
  compose/docker-compose.yml   local dev: postgres, rabbitmq, redis, minio
/docs/superpowers/specs        these specs
```

### Module dependency rules
- `common` depends on nothing internal.
- `domain` → `common`.
- `spec-engine` → `common`. It has no Spring dependency and is pure Java, so it is easy to unit test.
- `generators/*` → `generators/core` → `spec-engine`, `common`. Generators are pure functions: `ApiModel + GeneratorConfig → List<GeneratedFile>`. They do no I/O.
- `vcs` → `common`. It is the only module that talks to GitHub.
- `ai` → `common`.
- `api-app` → `domain`, `vcs`, `spec-engine`, `common`, `ai`.
- `worker-app` → everything except `api-app`.

An ArchUnit test in CI enforces these rules.

---

## 3. Runtime architecture

```
 Browser (React SPA)
    │  HTTPS (REST + SSE), session cookie
    ▼
 api-app (N replicas) ──── Postgres
    │   │  │                  ▲
    │   │  └── Redis (sessions, log pub/sub, rate limits)
    │   │                     ▲
    │   └─ publish ──▶ RabbitMQ ──▶ worker-app (M replicas)
    │                                   │   │
 GitHub ◀── webhooks/API ───────────────┘   └─▶ K8s Job (toolchain sandbox)
                                                 tsc / prettier / ruff / javac
 S3/MinIO ◀── run artifacts (zip) ── worker-app
```

### Messaging
- Exchange `emitkit.jobs` (direct). Queue `generation.jobs`. Message `{ runId, attempt }`.
- Retry: on a transient failure the message goes to `generation.retry.{10s,60s,300s}` (TTL queues that dead-letter back to the main queue). After 3 attempts it goes to `generation.dlq` and the run is marked `FAILED`.
- Cancellation: the API sets `generation_runs.cancel_requested = true` and publishes to the fanout exchange `emitkit.control`. Workers check the flag between pipeline steps and on every log flush.
- Delivery is at-least-once, so consumers must be idempotent. Each step checks the persisted run state before acting (see v1 §5).

### Live logs
- The worker appends log lines to Redis channel `run-logs:{runId}` and buffers them. Every 2 s, and at the end of each step, it writes the buffer to `generation_run_logs` in Postgres.
- The API's `GET /api/runs/{id}/logs/stream` uses `SseEmitter`. It first replays the stored logs, then subscribes to Redis, and closes on a `[DONE]` event.

### Security baseline
- Spring Security OAuth2 Login (GitHub). Session in Redis via Spring Session. Cookie is `HttpOnly; Secure; SameSite=Lax`.
- CSRF protection on for cookie-authenticated endpoints. The SPA reads the `XSRF-TOKEN` cookie.
- Webhooks use their own filter chain (no session, HMAC verification only).
- All secrets (GitHub tokens, webhook secrets, AI keys, registry tokens) are encrypted at rest with AES-256-GCM. The key comes from a Kubernetes Secret, and a key-version prefix allows key rotation.
- Authorization: every org-scoped request goes through `OrgAccessService.require(orgId, Permission)`. Permissions are listed per role in v1 §3.
- CORS allowlist is set per environment.

### Observability
- Spring Boot Actuator (`/actuator/health`, `/actuator/prometheus`) on a separate management port.
- Micrometer metrics: `emitkit.runs.duration`, `emitkit.runs.status`, `emitkit.github.ratelimit.remaining`, queue depth.
- Structured JSON logs (Logback + logstash encoder) with `runId`, `orgId` and `traceId`.
- OpenTelemetry tracing across api → RabbitMQ → worker.

---

## 4. Frontend baseline

- React 19, Vite, TypeScript (strict).
- TanStack Router (file-based) and TanStack Query.
- shadcn/ui + Tailwind CSS v4, light/dark theme, sonner toasts.
- CodeMirror 6 for the spec editor, `@apidevtools/swagger-parser` for live validation in the browser.
- API client generated from the backend's springdoc OpenAPI document (`openapi-typescript` + `openapi-fetch`), regenerated in CI so a mismatch fails the build.
- Testing: Vitest + Testing Library; Playwright for end-to-end tests against docker-compose.

---

## 5. Cross-cutting conventions

- **IDs:** ULID strings, generated in Java.
- **Time:** `timestamptz` in UTC; ISO-8601 in the API.
- **API errors:** RFC 9457 Problem Details (`type`, `title`, `status`, `detail`, `code`). Every error has a stable `code` such as `SPEC_CONFLICT` or `FORBIDDEN`.
- **Pagination:** cursor based (`?cursor=&limit=`), with a maximum limit of 100.
- **API versioning:** every endpoint is under `/api/v1/...`.
- **Feature flags:** a simple table-backed flag service, so v2/v3 features can ship dark.

---

## 6. Testing strategy

| Layer | Tooling | What it covers |
|---|---|---|
| spec-engine | JUnit 5, fixture specs | parser, differ, semver, linter |
| generators | Golden-file snapshot tests | exact output per target for ~10 fixture specs |
| generators (compile) | Testcontainers toolchain image | generated code passes tsc / ruff / javac |
| domain / api-app | Spring Boot tests + Testcontainers (Postgres, Redis, RabbitMQ) | repositories, controllers, authorization |
| vcs | WireMock | GitHub API calls, rate-limit handling |
| worker-app | Testcontainers + WireMock | full pipeline end to end |
| architecture | ArchUnit | module dependency rules |
| frontend | Vitest, Playwright | components, critical user flows |

CI is GitHub Actions: build, test, Docker build, Helm lint. Coverage gate is 80% lines on `spec-engine` and `generators`.

---

## 7. Phase summary

| Phase | Theme | Exit criteria |
|---|---|---|
| **v1** | Feature parity + core additions | Parity checklist passes in staging; TS code deleted; deployed to production K8s |
| **v2** | Teams, notifications, more targets, publishing | All v2 features behind flags, enabled in production |
| **v3** | AI features, GitLab/Bitbucket | AI features GA; at least one non-GitHub VCS GA |
