# Production deploy (Docker Compose)

Runbook for the single-host production stack in `deploy/compose/docker-compose.prod.yml`: first install, Vault unseal, upgrade.

## What runs

| Service | Replicas | Notes |
|---|---|---|
| `caddy` | 1 | The SPA from `frontend/`, TLS, reverse proxy to `api`. The only service with published ports (80, 443). |
| `api` | 2 | `api-app`. Health: `/actuator/health/liveness` on the management port 8081. |
| `worker` | 2 | `worker-app`. Gets only the sandbox daemon socket `/run/emitkit-sandbox/docker.sock`. |
| `vault` | 1 | Vault server with Raft storage. Starts sealed. |
| `postgres`, `rabbitmq`, `redis`, `rustfs` | 1 | Named volumes. `rustfs-init` creates the artifact bucket. |

Networks: `edge` (Caddy only), `app` (internal, everything else) and `egress` (non-internal, `api` and `worker` only, for outbound calls). Nothing else is published on the host.

## Before you start

- A Linux host with Docker Engine and the Compose plugin, and a checkout of the release tag.
- DNS for the public host name points at the host, and ports 80 and 443 are open.
- The SBX-01 sandbox daemon runs and listens on `/run/emitkit-sandbox/docker.sock`. Set it up with `sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootful`. The worker fails to start without this socket, because `create_host_path: false` stops Docker from creating an empty directory in its place.
- A GitHub OAuth app for sign-in, with its callback URL on the public host name.

All commands below run from `deploy/compose`:

```sh
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
```

## First install

1. Create the env file and fill in every empty value:

   ```sh
   cp .env.prod.example .env.prod
   openssl rand -base64 32   # one value each for POSTGRES_PASSWORD, RABBITMQ_PASSWORD, RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY
   getent group emitkit-sandbox | cut -d: -f3   # the value for SANDBOX_GID (the example's 10010 is a sample)
   ```

   Also fill `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` from the GitHub OAuth app. Set `API_VAULT_ROLE_ID` and `WORKER_VAULT_ROLE_ID` to `pending` for now: compose refuses an empty value, and step 6 replaces them. Compose refuses to start while any secret is empty, so a forgotten value cannot leave a stack running on an example password.

   `.env.prod` and `secrets/` are git-ignored. Keep them out of the repository.

2. Start Vault. It comes up sealed, and `--wait` still passes because a sealed Vault is reachable:

   ```sh
   $COMPOSE up -d --wait vault
   ```

3. Initialize Vault once. Save the 5 unseal shares and the root token in separate offline places:

   ```sh
   $COMPOSE exec vault vault operator init -key-shares=5 -key-threshold=3
   ```

4. Unseal it with 3 of the 5 shares (see [Unseal](#unseal)).

5. Bootstrap Transit, AppRole and the policies, using the root token. The commands run inside the `vault` container, and `-e VAULT_TOKEN` passes the token from your shell:

   ```sh
   export VAULT_TOKEN=<root token>
   V="$COMPOSE exec -T -e VAULT_TOKEN vault vault"
   $V secrets enable transit
   $V auth enable approle
   $V write transit/keys/emitkit-secrets type=aes256-gcm96 derived=true exportable=false
   $V policy write emitkit-api - < vault/policies/emitkit-api.hcl
   $V policy write emitkit-worker - < vault/policies/emitkit-worker.hcl
   $V write auth/approle/role/emitkit-api token_policies=emitkit-api token_ttl=1h
   $V write auth/approle/role/emitkit-worker token_policies=emitkit-worker token_ttl=1h
   ```

6. Put the role IDs in `.env.prod` (`API_VAULT_ROLE_ID`, `WORKER_VAULT_ROLE_ID`):

   ```sh
   $V read -field=role_id auth/approle/role/emitkit-api/role-id
   $V read -field=role_id auth/approle/role/emitkit-worker/role-id
   ```

7. Write the secret IDs to host files. The containers read them as Docker secrets, so the files must be readable by uid 10001:

   ```sh
   mkdir -p secrets
   $V write -f -field=secret_id auth/approle/role/emitkit-api/secret-id > secrets/api-secret-id
   $V write -f -field=secret_id auth/approle/role/emitkit-worker/secret-id > secrets/worker-secret-id
   sudo chown 10001:10001 secrets/api-secret-id secrets/worker-secret-id
   chmod 0400 secrets/api-secret-id secrets/worker-secret-id
   ```

8. Revoke the root token. Use a fresh root token from `vault operator generate-root` for any later admin task:

   ```sh
   $V token revoke -self
   ```

9. Start the stack. The apps do not need Vault at start-up. Requests that need a decrypt return 503 until Vault is unsealed:

   ```sh
   $COMPOSE up -d --wait --build
   ```

10. Check it. Expect `401` from the API when no session is sent, and the SPA shell from `/`:

    ```sh
    curl -s -o /dev/null -w '%{http_code}\n' https://<public host>/api/v1/me
    curl -s https://<public host>/ | grep -c '<div id="root">'
    ```

## Unseal

Vault starts sealed after every restart of the container or the host, and after any recreate of the `vault` service. Unseal it with any 3 of the 5 shares:

```sh
for i in 1 2 3; do
  read -rs -p "Unseal share $i: " share; echo
  $COMPOSE exec -T vault vault operator unseal "$share" >/dev/null
done
$COMPOSE exec -T vault vault status   # Sealed: false
```

Shares are typed at the prompt and must never be written to a file or the shell history. If secret-using endpoints still return `SECRETS_UNAVAILABLE` after unsealing, restart the apps: `$COMPOSE restart api worker`.

## Upgrade

1. Back up first:

   ```sh
   mkdir -p backups
   $COMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > backups/emitkit-$(date +%F).sql
   ```

   Also take a Raft snapshot with an admin token: `$V operator raft snapshot save /vault/file/pre-upgrade.snap`, then copy it out with `$COMPOSE cp vault:/vault/file/pre-upgrade.snap backups/`.

2. Check out the new release tag and rebuild:

   ```sh
   git fetch --tags && git checkout <tag>
   cd deploy/compose && $COMPOSE up -d --wait --build
   ```

   Compose recreates only the services whose config or image changed. An unchanged `vault` keeps running, so it stays unsealed. If `vault` was recreated, unseal it again.

3. Run the checks from step 10 of [First install](#first-install).

**Rollback** means checking out the previous tag and running `up -d --wait --build` again. There is no image registry in this stack, so the old images are rebuilt from source. Database migrations are not reversed automatically. If the new release migrated the schema, restore the Postgres backup from step 1 as well.

## Known gaps

- Traffic between the app containers and Vault, Postgres, RabbitMQ, Redis and RustFS is plain text on the `app` network. Vault has `tls_disable = true`. Redis has no password.
- `app` is `internal: true`. `api` and `worker` also join `egress` (not internal) for outbound calls such as GitHub. Nothing on `egress` publishes a port. Anything that must not reach the internet belongs on `app` only.
- The worker's sandbox socket is root-equivalent on the host. See `docs/spikes/2026-gvisor-rootless.md`, "Security under B". Who may join the `emitkit-sandbox` group is still to be decided.
- Vault runs as a single node, unsealed by hand. Raft snapshots every 6 h and the nightly backups are not automated here. High availability and auto-unseal are v4.
- The worker has no health endpoint, so it has no healthcheck. `up --wait` only waits for it to start.
- Caddy's health checks on the api replicas are passive. Caddy's active checks do not run for the Docker DNS upstreams, so a replica is marked down only after a real request fails: a refused connection, or no response headers within 5 s. It is then skipped for 30 s and the request is retried on the other replica. Docker's `healthy` status for the api is a separate, active liveness check on port 8081 and does not feed Caddy. A replica that is paused or hung is therefore detected per request, not by a probe.
