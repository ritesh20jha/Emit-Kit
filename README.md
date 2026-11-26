# EmitKit

EmitKit turns an OpenAPI spec in a GitHub repository into client libraries and opens a pull request with the result. It generates TypeScript, Python and Java SDKs, a CLI, an MCP server, docs and release workflows, and tags a release when the pull request merges.

The backend is a Java 21 / Spring Boot 4 modular monolith (`api-app` and `worker-app`). The frontend is a React 19 SPA. Both live in this repository.

## How it works

1. A user signs in with GitHub and connects a repository that holds an OpenAPI spec.
2. A push to the default branch, or a manual run, queues a generation job on RabbitMQ.
3. The worker fetches and parses the spec, lints it, diffs it against the last version, and picks the SemVer bump.
4. It runs the generators inside a locked-down sandbox container, checks that the output compiles, packages it, and commits it to a branch.
5. It opens a pull request. When that pull request merges, the release is tagged.

Run logs stream to the browser over SSE. Secrets are encrypted through Vault Transit. Artifacts are stored in S3-compatible storage (RustFS).

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Gradle multi-module build: `common`, `domain`, `vcs`, `spec-engine`, `generators/*`, `ai`, `api-app`, `worker-app`, `architecture-tests`. See [backend/README.md](backend/README.md). |
| `frontend/` | React SPA (Vite, pnpm, TanStack Router and Query, Tailwind v4, shadcn/ui, CodeMirror 6), Storybook and the Playwright suites. |
| `deploy/` | Compose stacks (local, production, smoke, e2e), the Caddyfile, Vault bootstrap, backup scripts, the toolchain image and the sandbox daemon setup. |
| `perf/` | k6 load tests. |
| `scripts/` | CI helpers such as `pr-size.sh` and the restore drill. |
| `docs/` | Runbooks, spikes and the design specs. |

## Quick start

You need Docker with the Compose plugin, JDK 21 (Gradle can provision it) and Node with pnpm.

```sh
# 1. Dependencies: Postgres, RabbitMQ, Redis, RustFS and Vault
docker compose -f deploy/compose/docker-compose.yml up -d --wait
bash deploy/compose/verify.sh          # prints "vault ok"

# 2. API (from backend/)
cd backend
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID=<oauth app id>
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET=<oauth app secret>
./gradlew :api-app:bootRun

# 3. Frontend (from frontend/)
cd ../frontend
pnpm install
pnpm dev
```

The local stack's ports and credentials are listed in [deploy/compose/README.md](deploy/compose/README.md).

GitHub has to reach your machine to deliver webhooks. Expose it with a tunnel such as ngrok and set `EMITKIT_PUBLIC_URL` to the public URL before you start the API.

The worker only consumes jobs when `emitkit.worker.pipeline.enabled=true`. It also needs the sandbox daemon and the toolchain image described in [docs/runbooks/deploy.md](docs/runbooks/deploy.md).

## Tests

Backend, from `backend/`:

```sh
./gradlew build          # compile, test and check (includes Spotless)
./gradlew test           # unit tests
./gradlew spotlessApply  # reformat Java sources
```

Integration tests end in `IT` and use Testcontainers, so Docker must be running.

Frontend, from `frontend/`:

```sh
pnpm lint
pnpm build               # type-check and bundle
pnpm vitest run          # unit tests
pnpm test:e2e            # Playwright against mocked APIs
pnpm test:parity         # full-stack parity suite, needs the e2e compose stack
pnpm storybook
```

The parity suite runs against the production stack plus the e2e overlay. Start it with `deploy/compose/e2e.sh up` and stop it with `deploy/compose/e2e.sh down`. Load tests are in `perf/`.

## CI

| Workflow | What it checks |
|---|---|
| Backend, E2E Backend | Gradle build and tests; the worker pipeline end to end |
| Frontend | Lint, unit tests, build, bundle size, Playwright specs |
| E2E Parity | The Playwright parity suite on the full stack |
| Compose, Compose prod | The local and production stacks start and pass their smoke checks |
| Toolchain | The sandbox toolchain image builds |
| Security | OWASP dependency-check (backend) and `pnpm audit` (frontend) |
| PR size | At most 1000 changed lines per PR; lockfiles, generated clients and golden files do not count |

## Deploy and operate

- [Production deploy](docs/runbooks/deploy.md): first install, Vault unseal, upgrade.
- [Backups and restore](docs/runbooks/restore.md): what is backed up, retention and the restore drill.
- [gVisor and rootless Docker spike](docs/spikes/2026-gvisor-rootless.md): why the sandbox is set up the way it is.

## Design

- [Overview](docs/superpowers/specs/2026-10-31-emitkit-rebuild-overview-design.md)
- [v1 design](docs/superpowers/specs/2026-10-31-emitkit-rebuild-v1-design.md)

## Contributing

Use Conventional Commits. Keep each pull request under 1000 changed lines and land its tests with it. Backend module dependencies are enforced by ArchUnit in `backend/architecture-tests`.
