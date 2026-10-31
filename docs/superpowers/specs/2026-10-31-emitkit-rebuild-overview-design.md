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
| Secret encryption | HashiCorp Vault **Transit engine only** (encryption as a service). Vault is not used for config, DB credentials or anything else |

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
  common/                      error types, Result helpers, SecretCipher (Vault Transit / local AES), ids (ULID)
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
  compose/docker-compose.yml   local dev: postgres, rabbitmq, redis, minio, vault (dev mode)
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
- All stored secrets (GitHub tokens, webhook secrets, AI keys, registry tokens) are encrypted through **Vault Transit**. See §3.1.
- Authorization: every org-scoped request goes through `OrgAccessService.require(orgId, Permission)`. Permissions are listed per role in v1 §3.
- CORS allowlist is set per environment.

### 3.1 Secret encryption with Vault Transit

Vault is used **only** for the Transit secrets engine. The encryption key is created in Vault and never leaves it (`exportable=false`). EmitKit sends plaintext and gets back ciphertext, and the reverse.

**Setup**
- Transit key `emitkit-secrets`, type `aes256-gcm96`, `derived=true`. Each call passes a `context`, so a ciphertext copied into another row or column fails to decrypt.
  - Context = base64 of `{table}:{column}:{rowId}`, e.g. `projects:webhook_secret_enc:01J…`.
- Auth: the **Kubernetes auth method**. `api-app` and `worker-app` service accounts log in with their pod token. Each role has a policy that only allows:
  ```hcl
  path "transit/encrypt/emitkit-secrets" { capabilities = ["update"] }
  path "transit/decrypt/emitkit-secrets" { capabilities = ["update"] }
  path "transit/rewrap/emitkit-secrets"  { capabilities = ["update"] }   # api-app only (rotation job)
  ```
- Client: `spring-vault-core` (`VaultTransitOperations`). Spring Cloud Vault config loading stays off, so Vault is not a config source.

**Code**
```java
public interface SecretCipher {
  String encrypt(String plaintext, SecretContext ctx);   // returns "vault:v{n}:..."
  String decrypt(String ciphertext, SecretContext ctx);
  List<String> decryptBatch(List<CipherItem> items);     // one Transit call per batch
}
```
- `VaultTransitCipher` is used in every deployed environment. `LocalAesCipher` (in-process AES-GCM with a fixed test key) is used **only** in unit tests and is refused at startup when `spring.profiles.active` includes `prod` or `staging`.
- Ciphertext is stored exactly as Vault returns it (`vault:v3:…`). The `v{n}` part is the key version, which is what makes rotation possible.
- Plaintext is never logged or cached. Decrypted values live only for the request or step that needs them.

**Key rotation**
- Yearly, or on demand: `vault write -f transit/keys/emitkit-secrets/rotate`.
- A ShedLock-guarded job in `api-app` re-wraps all `*_enc` columns in batches of 200 with `transit/rewrap`. The plaintext never reaches the app during re-wrap.
- After the job reports zero rows below the new version, raise `min_decryption_version` so old key versions can't be used.

**Availability & failure**
- Production: Vault in HA mode (3 nodes, Raft storage, auto-unseal via cloud KMS) or HCP Vault. A target of 99.9%, which is higher than the API's 99.5%, because every decrypt depends on it.
- If Vault is unreachable:
  - Webhooks return **503**, so GitHub redelivers.
  - Worker steps throw `SecretBackendException`, which counts as **transient** and goes through the normal retry queues.
  - User requests that need a decrypt return 503 `SECRETS_UNAVAILABLE`.
  - Endpoints that don't need a secret keep working.
- `/actuator/health` includes a `vault` indicator (a Transit encrypt+decrypt of a fixed value), part of the readiness group only, so a Vault outage doesn't restart the pods.
- Metrics: `emitkit.vault.requests{op,status}` and latency.

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
