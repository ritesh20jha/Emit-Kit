# T03 · Local dev stack (docker-compose)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One command starts Postgres, RabbitMQ, Redis, RustFS and a Vault dev server with the Transit key, policies and AppRole roles ready.

**Lane:** PLAT · **Depends on:** — · **Size budget:** ~350 changed lines (hard limit 1000) · **Branch:** `ticket/t03`

**Spec:** ov §2 (compose), ov §3.1 (Transit key, policies, AppRole)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `deploy/compose/docker-compose.yml`, `deploy/compose/.env.example`
- Create: `deploy/compose/vault/init.sh`, `deploy/compose/vault/policies/emitkit-api.hcl`, `deploy/compose/vault/policies/emitkit-worker.hcl`
- Create: `deploy/compose/verify.sh`, `deploy/compose/README.md`
- Create: `.github/workflows/compose.yml`

## Interfaces

- **Produces (ports on localhost):** Postgres `5432` (db/user/pass `emitkit`), RabbitMQ `5672` + UI `15672` (`emitkit`/`emitkit`), Redis `6379`, RustFS S3 API `9000` (bucket `emitkit-artifacts`, created by an init container), Vault `8200` (dev root token `dev-root`).
- **Produces (files):** `deploy/compose/.secrets/{api,worker}-role-id` and `{api,worker}-secret-id` written by `vault-init` (git-ignored) — used by CORE-02 and local app runs.
- **Vault objects (exact):** transit key `emitkit-secrets` (`type=aes256-gcm96 derived=true exportable=false`); policies `emitkit-api` (encrypt, decrypt, rewrap) and `emitkit-worker` (encrypt, decrypt) as in ov §3.1; AppRole roles `emitkit-api`, `emitkit-worker` bound to those policies, `token_ttl=1h`.

## Tasks

### Task 1: Compose services

- [ ] **Step 1: Write `docker-compose.yml`** with pinned image tags (postgres:16, rabbitmq:3-management, redis:7, a pinned `rustfs/rustfs` release, hashicorp/vault pinned), named volumes, healthchecks for every service, and one-shot services `rustfs-init` (creates bucket with an S3 CLI image) and `vault-init` (runs `init.sh`, `depends_on: vault: service_healthy`).
- [ ] **Step 2: Verify** — `docker compose -f deploy/compose/docker-compose.yml up -d --wait` exits 0.
- [ ] **Step 3: Commit** — `git commit -m "chore(dev): compose stack for local development"`

### Task 2: Vault bootstrap + verification

- [ ] **Step 1: Write `verify.sh` first** (it is the test):

```bash
# logs in with the api AppRole, then:
vault write -field=ciphertext transit/encrypt/emitkit-secrets \
  plaintext=$(echo -n hello | base64) context=$(echo -n "t:c:1" | base64) | grep -q '^vault:v1:'
# key export must be denied
! vault read transit/export/encryption-key/emitkit-secrets 2>/dev/null
# worker role must NOT be allowed to rewrap
! VAULT_TOKEN=$WORKER_TOKEN vault write transit/rewrap/emitkit-secrets ciphertext=$CT context=$CTX 2>/dev/null
echo "vault ok"
```
- [ ] **Step 2: Run — expect FAIL** (no key yet). Run: `bash deploy/compose/verify.sh`
- [ ] **Step 3: Implement `init.sh`** (idempotent: `vault secrets list | grep -q transit/ || vault secrets enable transit`, same pattern for approle, key, policies, roles; writes role/secret ids to `/secrets`).
- [ ] **Step 4: Run — expect `vault ok`.**
- [ ] **Step 5: Add `.github/workflows/compose.yml`** running `up -d --wait` + `verify.sh` on changes to `deploy/compose/**`.
- [ ] **Step 6: Commit** — `git commit -m "chore(dev): bootstrap vault transit and approle"`

---

## Done when

- [ ] All tasks above are committed on `ticket/t03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
