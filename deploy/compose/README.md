# Local dev stack

Postgres, RabbitMQ, Redis, RustFS (S3) and a Vault dev server, with the Vault Transit key, policies and AppRoles ready.

## Start

```bash
docker compose -f deploy/compose/docker-compose.yml up -d --wait
bash deploy/compose/verify.sh   # prints "vault ok"
```

To override the defaults, copy `.env.example` to `.env` (git-ignored).

## Ports and credentials

| Service | Address | Credentials |
|---|---|---|
| Postgres | localhost:5432 | db, user and password `emitkit` |
| RabbitMQ | localhost:5672 (UI: localhost:15672) | `emitkit` / `emitkit` |
| Redis | localhost:6379 | none |
| RustFS (S3) | localhost:9000 | `emitkit` / `emitkit-secret`, bucket `emitkit-artifacts` |
| Vault | http://localhost:8200 | root token `dev-root` |

## What vault-init produces

`vault-init` runs `vault/init.sh` on start. It enables Transit and AppRole, creates the transit key `emitkit-secrets`, writes the policies in `vault/policies/`, and creates the AppRoles `emitkit-api` and `emitkit-worker`. It writes their IDs to `deploy/compose/.secrets/`:

- `api-role-id`, `api-secret-id`
- `worker-role-id`, `worker-secret-id`

`.secrets/` is git-ignored. Its files are world-readable on purpose, because this is dev only.

## Verify

`verify.sh` logs in as both AppRoles and checks that the Transit round-trip works, that key export is denied, and that the worker cannot rewrap. It also checks the key's type, derived and exportable settings. It uses a host `vault` CLI if one is installed, and otherwise the CLI inside the `vault` container.

## Notes

- Vault runs in dev mode, so its state is in memory and resets when the container restarts. If you restart `vault` alone, re-bootstrap it with `docker compose -f deploy/compose/docker-compose.yml up -d --wait --force-recreate vault-init`.
- If you hit data-directory errors after pulling this stack from an older checkout, run `docker compose -f deploy/compose/docker-compose.yml down -v` once.
- To reset everything, run `docker compose -f deploy/compose/docker-compose.yml down -v`.
