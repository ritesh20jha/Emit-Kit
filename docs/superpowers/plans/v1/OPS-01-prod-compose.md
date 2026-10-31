# OPS-01 · Production images + compose + Caddy

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Production Dockerfiles and the Compose stack with Caddy, two api and two worker containers, Vault server mode and the sandbox daemon socket.

**Lane:** OPS · **Depends on:** [API-00](API-00-api-skeleton.md), [WRK-00](WRK-00-worker-skeleton.md), [T04](T04-frontend-scaffold.md), [SBX-01](SBX-01-gvisor-spike.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/ops-01`

**Spec:** ov §3 (runtime, only 80/443 published), §3.1 (AppRole secret via Docker secrets, Vault server + Raft)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `deploy/docker/api.Dockerfile`, `worker.Dockerfile`, `web.Dockerfile` (multi-stage, non-root, Temurin 21 JRE / Caddy for web)
- Create: `deploy/compose/docker-compose.prod.yml`, `deploy/compose/Caddyfile`, `deploy/compose/vault/vault.hcl` (Raft storage), `deploy/compose/.env.prod.example`
- Create: `deploy/compose/smoke.sh`, `.github/workflows/compose-prod.yml`
- Create: `docs/runbooks/deploy.md` (first install, unseal, upgrade)

## Interfaces

- **Produces:**
- Networks: `edge` (caddy only, ports 80/443), `app` (internal: api, worker, postgres, rabbitmq, redis, rustfs, vault).
- `api` ×2, `worker` ×2 (`deploy.replicas`), healthchecks on management port; Vault health deliberately not in app healthchecks (ov §3.1).
- Secrets: `api_vault_secret_id`, `worker_vault_secret_id` as Docker secrets → `/run/secrets/...` → `emitkit.vault.secret-id-file`.
- Worker mounts only the sandbox daemon socket from SBX-01 (never `/var/run/docker.sock` of the host daemon).
- Caddyfile: SPA from `/srv` with `try_files {path} /index.html`; `/api/*`, `/oauth2/*`, `/login/*`, `/webhooks/*` → api upstream (round-robin, health checks); `flush_interval -1` for `/api/v1/runs/*/logs/stream`; security headers (HSTS, `X-Content-Type-Options`, CSP `default-src 'self'`).
- Docker `local` log driver with rotation.

## Tasks

### Task 1: Production images + compose + Caddy

- [ ] **Step 1: Write the failing test**

```bash
# deploy/compose/smoke.sh
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml up -d --wait   # with test certs + dev-unsealed vault override
curl -fsk https://localhost/api/v1/me -o /dev/null -w '%{http_code}' | grep -q 401
curl -fsk https://localhost/ | grep -q '<div id="root">'
! nc -z localhost 5432 && ! nc -z localhost 8200        # nothing but 80/443 published
docker compose exec worker sh -c 'test ! -S /var/run/docker.sock'
echo "prod stack ok"
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `bash deploy/compose/smoke.sh`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `bash deploy/compose/smoke.sh`

- [ ] **Step 5: Commit** — `git commit -m "ops: production images + compose + caddy"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ops-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
