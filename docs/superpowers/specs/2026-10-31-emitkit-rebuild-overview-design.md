# EmitKit Rebuild — Overview & Shared Architecture

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Phase specs:**
- [v1 — Parity + core additions](2026-10-31-emitkit-rebuild-v1-design.md)
- [v2 — Scale & ecosystem](2026-10-31-emitkit-rebuild-v2-design.md)
- [v3 — AI & multi-VCS](2026-10-31-emitkit-rebuild-v3-design.md)
- [v4 — Observability & Kubernetes](2026-10-31-emitkit-rebuild-v4-design.md)

---

## 1. Goal

Rebuild EmitKit from scratch on **Java 21 + Spring Boot 4** (backend) and **React 19** (frontend) as a **production multi-tenant SaaS**. The rebuild keeps every feature of the current Bun/TypeScript app and adds the new features agreed for v1–v4.

EmitKit connects to a user's GitHub repositories, watches an OpenAPI spec, and generates SDKs, CLIs, MCP servers and docs. It opens pull requests with the generated code and tags releases when those PRs are merged.

### Decisions

| Topic | Decision |
|---|---|
| Purpose | Production SaaS (real users, multiple orgs) |
| Repo | Same repo. The TS code is deleted once v1 passes its parity checklist |
| Backend shape | Gradle modular monolith with two deployables: `api-app` and `worker-app` |
| Framework | **Spring Boot 4** (Spring Framework 7) |
| Database | PostgreSQL 16, migrations with Flyway |
| Queue | RabbitMQ (jobs, retries, dead-letter queues) |
| Cache / pub-sub | Redis (live log fan-out, Spring Session, rate-limit counters) |
| Object storage | S3-compatible: **RustFS**, for run artifacts |
| Generators | Our own templates: swagger-parser → internal `ApiModel` → Mustache templates per target |
| AI | **Spring AI**. Gemini is the default provider; the user supplies the key |
| Secret encryption | HashiCorp Vault **Transit engine only** (encryption as a service). Vault is not used for config, DB credentials or anything else |
| Deployment (v1–v3) | **Docker Compose** on a Linux host. Syntax checks run in short-lived, locked-down Docker containers under **gVisor** (`runsc`) |
| Deployment (v4) | Kubernetes + Helm, moved to v4 along with observability (see the [v4 spec](2026-10-31-emitkit-rebuild-v4-design.md)) |

### Spring Boot 4 notes
- **Jackson 3** (`tools.jackson.*`) is the default in Boot 4. `swagger-parser` still uses Jackson 2 (`com.fasterxml.jackson.*`). The two coexist because their package names differ. `spec-engine` uses Jackson 2 internally and exposes only its own `ApiModel` types, so Jackson 2 doesn't leak into the Spring layers.
- Use versions built for Boot 4: springdoc-openapi 3.x, Spring AI 2.x, ShedLock, Testcontainers 2.x. Check each library for Boot 4 support when a milestone starts; if one has none, note it in that milestone's plan.
- `spring-boot-starter-*` packages are more modular in Boot 4, so each app declares only the starters it uses.

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
  ai/                          Spring AI wrapper: ChatModelFactory, prompts, structured-output types
  api-app/                     Spring Boot app: REST, security, webhooks, SSE
  worker-app/                  Spring Boot app: RabbitMQ consumers, pipeline, sandbox runner
/frontend                      React 19 + Vite + TS
/deploy
  docker/                      Dockerfiles (api, worker, web, toolchain)
  compose/
    docker-compose.yml         local dev: postgres, rabbitmq, redis, rustfs, vault (dev mode)
    docker-compose.prod.yml    production: + caddy, api ×2, worker ×2, vault (server mode)
/docs/superpowers/specs        these specs
```

### Module dependency rules
- `common` depends on nothing internal.
- `domain` → `common`.
- `spec-engine` → `common`. It has no Spring dependency and is pure Java, so it is easy to unit test.
- `generators/*` → `generators/core` → `spec-engine`, `common`. Generators are pure functions: `ApiModel + GeneratorConfig → List<GeneratedFile>`. They do no I/O. The docs generator gets AI help through an interface in `generators/core` that `ai` implements, so generators never depend on Spring AI.
- `vcs` → `common`. It is the only module that talks to GitHub.
- `ai` → `common`, `generators/core` (to implement the docs interface).
- `api-app` → `domain`, `vcs`, `spec-engine`, `common`, `ai`.
- `worker-app` → everything except `api-app`.

An ArchUnit test in CI enforces these rules.

---

## 3. Runtime architecture

```
 Browser (React SPA)
    │  HTTPS (REST + SSE), session cookie
    ▼
 Caddy (TLS, reverse proxy, static frontend)
    │
 api-app (×2) ──── Postgres
    │   │  │           ▲
    │   │  └── Redis (sessions, log pub/sub, rate limits)
    │   │              ▲
    │   └─ publish ──▶ RabbitMQ ──▶ worker-app (×2)
    │                                   │   │
 GitHub ◀── webhooks/API ───────────────┘   └─▶ Docker sandbox container (toolchain)
                                                 tsc / prettier / ruff / javac
 RustFS ◀── run artifacts (zip) ── worker-app
 Vault  ◀── Transit encrypt/decrypt ── api-app, worker-app
```

v1–v3 run on one Docker Compose host. Postgres, RabbitMQ, Redis, RustFS and Vault use named volumes with nightly backups (v1 §11). Multi-node high availability is part of v4.

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
- Only Caddy's ports 80/443 are published on the host. Every other container is on an internal Docker network.

### 3.1 Secret encryption with Vault Transit

Vault is used **only** for the Transit secrets engine. The encryption key is created in Vault and never leaves it (`exportable=false`). EmitKit sends plaintext and gets back ciphertext, and the reverse.

**Setup**
- Transit key `emitkit-secrets`, type `aes256-gcm96`, `derived=true`. Each call passes a `context`, so a ciphertext copied into another row or column fails to decrypt.
  - Context = base64 of `{table}:{column}:{rowId}`, e.g. `projects:webhook_secret_enc:01J…`.
- Auth (v1–v3): the **AppRole auth method**. `api-app` and `worker-app` each have their own role. The `role_id` is passed as an environment variable. The `secret_id` is read from a file mounted with Docker secrets, so it never appears in `docker inspect`. Each role has a policy that only allows:
  ```hcl
  path "transit/encrypt/emitkit-secrets" { capabilities = ["update"] }
  path "transit/decrypt/emitkit-secrets" { capabilities = ["update"] }
  path "transit/rewrap/emitkit-secrets"  { capabilities = ["update"] }   # api-app only (rotation job)
  ```
  v4 switches to the Kubernetes auth method.
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
- v1–v3: a single Vault server (Raft storage on a named volume) in the Compose stack. Unsealed by an operator after each restart, using 3-of-5 Shamir key shares held offline. Raft snapshots every 6 h. High availability and auto-unseal move to v4.
- If Vault is unreachable or sealed:
  - Webhooks return **503**, so GitHub redelivers.
  - Worker steps throw `SecretBackendException`, which counts as **transient** and goes through the normal retry queues.
  - User requests that need a decrypt return 503 `SECRETS_UNAVAILABLE`.
  - Endpoints that don't need a secret keep working.
- `/actuator/health` includes a `vault` indicator (a Transit encrypt+decrypt of a fixed value). It is shown in the health response but kept out of the Compose healthcheck, so a sealed Vault doesn't make Docker restart the apps.

### 3.2 Logging & health (v1–v3)
- Spring Boot Actuator exposes only `/actuator/health` (used by Docker healthchecks and Caddy), on a management port that isn't published.
- Plain-text Logback console logs with `runId` and `orgId` in the MDC, read with `docker compose logs`. Log rotation is done by Docker's `local` log driver.
- Metrics, tracing, structured logs, dashboards and alerting are part of **v4**.

---

## 4. Frontend

The frontend is **rebuilt from scratch**, like the backend. Today's `apps/web` is reference material for *what* the screens do, not *how* they're built: **no code, styles or components are copied from it.** It is deleted together with the rest of the TS code at the end of v1.

### 4.1 What's wrong with today's frontend (and the rule that fixes it)

| Problem in `apps/web` | Rule for the rebuild |
|---|---|
| Generic "AI-made" look: glass blur (21×), gradient text, hard-coded `indigo-*` everywhere (80+×) | One design system with tokens (§4.3); a lint rule bans raw palette classes in feature code |
| Text too small: `text-[10px]` used 38×, tiny uppercase labels | Minimum 12 px, body 14 px, sentence-case labels |
| Project tabs kept in `useState`, so they can't be linked or survive a refresh | Every view and tab is a route; filters live in URL search params (§4.4) |
| Big page files (project page 439 lines) mixing data, logic and markup | Thin routes, feature folders, ≤ 150 lines per route file (§4.5) |
| Zero `aria-*` attributes, unclear focus, weak empty/error states | WCAG 2.2 AA, four required states for every data view (§4.6) |

### 4.2 Stack
- React 19, Vite, TypeScript (strict), TanStack Router (file-based) and TanStack Query.
- shadcn/ui components **restyled to our tokens** + Tailwind CSS v4, Radix primitives underneath (accessible by default), sonner toasts, `cmdk` for the command palette, lucide icons.
- CodeMirror 6 (+ `@codemirror/merge` for diffs) for the spec editor and diff viewer; `@apidevtools/swagger-parser` for live validation in the browser.
- react-hook-form + zod for forms.
- API client generated from the backend's springdoc OpenAPI document (`openapi-typescript` + `openapi-fetch`), regenerated in CI so a mismatch fails the build.
- Built as static files and served by Caddy (v1–v3).

### 4.3 Visual direction & design system: "precise dev tool"
- **Feel:** calm, dense, keyboard-first, in the family of GitHub, Linear and Vercel dashboards, but with EmitKit's own identity. Content first; chrome stays quiet.
- **Color:** neutral grey scale for surfaces and text; **one** accent color for primary actions, links and focus rings; four status colors (success, running, failed, awaiting approval) used only for status. No glass blur, gradients or glow.
- **Type:** Inter for the UI, JetBrains Mono for code, IDs, SHAs, versions and log lines. Scale 12 / 14 / 16 / 20 / 24 px. Body 14 px.
- **Spacing & shape:** 4 px grid; radius 6 px (controls) / 8 px (cards); 1 px borders instead of heavy shadows.
- **Motion:** 150 ms ease-out for hover and menus only, turned off under `prefers-reduced-motion`.
- **Tokens:** all of the above live as CSS variables in `ui/tokens.css` (`--color-bg`, `--color-fg-muted`, `--color-accent`, `--color-status-failed`, `--space-2`, …), mapped into Tailwind's theme. Light and dark themes are two sets of the same tokens, and both are tested.
- **Component library (`ui/`):** Button, IconButton, Input, Select, Checkbox, Switch, Textarea, Dialog, Sheet, DropdownMenu, Tabs (route-aware), Table, DataList (mobile), Badge, StatusPill, Toast, Tooltip, Skeleton, EmptyState, ErrorState, CodeBlock, Kbd, CommandPalette, PageHeader, Breadcrumbs. Every component has a Storybook story in both themes.

### 4.4 Navigation & URLs
- **App shell:** a left sidebar (org switcher at the top, then Projects, Members, Settings; v2 adds Analytics, Audit, Notifications), a top bar with breadcrumbs, the GitHub quota indicator, the theme toggle and the user menu. On mobile the sidebar becomes a drawer.
- **Command palette (⌘K / Ctrl+K):** jump to any project, run or page; run actions such as "Trigger run", "Open editor", "Switch org". Results are filtered by the user's permissions.
- **Keyboard shortcuts:** `g p` projects, `g r` runs, `/` focus search, `?` shortcut help, `⌘S` save in the editor.
- **Every view has a URL:** tabs are child routes (`/projects/$id/runs`, `/projects/$id/config`, …). Table filters, sorting, pagination cursors and selected files are search params, so any screen can be shared or bookmarked and survives a refresh.

### 4.5 Code architecture
```
/frontend/src
  app/          router setup, providers (Query, theme), layouts (app shell, auth guard)
  routes/       thin route files: loaders + composition of feature components (≤ 150 lines)
  features/     orgs/ members/ projects/ spec-editor/ spec-diff/ config/ runs/ versions/ …
                each: components/, hooks/, schemas.ts, api.ts (query options)
  ui/           design-system components + tokens.css; no business logic, no API calls
  lib/api/      generated client, query-key factory, ProblemDetails → AppError mapping
  lib/auth/     session (/me), CSRF header, 401 → login redirect, usePermission()
  lib/sse/      useEventStream() hook with reconnect + replay
  test/         MSW handlers, render helpers
```
- **Import rules** (enforced by `eslint-plugin-boundaries`): `routes → features → ui/lib`; features never import other features' internals (only their `index.ts`); `ui` imports nothing from `features`.
- **State:** server data lives only in TanStack Query (no Redux or Zustand). URL state lives in router search params. Local UI state stays in components.
- **Data loading:** route loaders call `queryClient.ensureQueryData`, so pages render with data instead of popping in. Mutations invalidate exact query keys from the key factory.
- **Auth:** a session cookie; the API client adds the `X-XSRF-TOKEN` header from the cookie; any 401 sends the user to `/login?next=…`. The `/me` query holds the user, orgs and roles.
- **Permissions:** `usePermission('RUN_TRIGGER', orgId)` and a `<Can>` wrapper. Actions the user can't perform are hidden; read-only views show a short "You have view access" note instead of edit controls. The backend still enforces every check.
- **Errors:** RFC 9457 responses map to an `AppError` with a stable `code`. Each code has a plain-language message (e.g. `SPEC_CONFLICT` → "Someone else changed this file. Reload to see their version."). Field errors are shown inline in forms; others as toasts or in-page error states.
- **Live data:** `useEventStream(url)` wraps `EventSource` with automatic reconnect, de-duplication by log `seq`, and a "Reconnecting…" banner.

### 4.6 States, accessibility & responsiveness
- **Four required states for every data view:**
  - **Loading:** a skeleton shaped like the final layout (no spinners for full pages).
  - **Empty:** says what the view is for and offers the next action ("No runs yet. Trigger the first run").
  - **Error:** the plain-language message, the error code in mono, and a Retry button.
  - **No permission:** what the user can't do and who can (the org admins).
- **Accessibility:** WCAG 2.2 AA. Every interactive element is reachable and usable by keyboard with a visible focus ring; dialogs trap focus; status is never shown by color alone (icon + label); live log updates use a polite live region; color contrast checked for both themes.
- **Responsive:** works from 360 px wide. Tables switch to `DataList` cards below 768 px; the editor and diff viewer switch to a single column with a file picker.
- **Performance:** route-level code splitting; CodeMirror loaded only on editor and diff routes; initial JS < 250 KB gzip; log view virtualized (100k lines).

### 4.7 Frontend testing
- **Unit / component:** Vitest + Testing Library, API mocked with MSW.
- **Storybook** for `ui/` and key feature components, with visual regression snapshots (Playwright screenshots of stories) in both themes.
- **Accessibility:** `@axe-core/playwright` on every main page in the E2E suite; zero serious or critical violations allowed.
- **End to end:** Playwright against docker-compose with WireMock-backed GitHub.

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
| domain / api-app | Spring Boot tests + Testcontainers (Postgres, Redis, RabbitMQ, Vault) | repositories, controllers, authorization |
| vcs | WireMock | GitHub API calls, rate-limit handling |
| worker-app | Testcontainers (incl. RustFS via `GenericContainer`) + WireMock | full pipeline end to end |
| architecture | ArchUnit | module dependency rules |
| frontend | Vitest, Playwright | components, critical user flows |

CI is GitHub Actions: build, test, Docker build, and a `docker compose config` check. Coverage gate is 80% lines on `spec-engine` and `generators`.

---

## 7. Phase summary

| Phase | Theme | Exit criteria |
|---|---|---|
| **v1** | Feature parity + core additions | Parity checklist passes in staging; TS code deleted; production running on Docker Compose |
| **v2** | Teams, notifications, more targets, publishing | All v2 features behind flags, enabled in production |
| **v3** | AI features, GitLab/Bitbucket | AI features GA; at least one non-GitHub VCS GA |
| **v4** | Observability & Kubernetes | Metrics, tracing, logs and alerts live; production moved to Kubernetes with HA data stores |

v4 doesn't depend on v2 or v3 features. It can start any time after v1, once you're ready to learn the tools.
