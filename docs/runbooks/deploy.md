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
| `backup` | 1 | Scheduled off-host backups of Postgres, Vault, RustFS and the WAL. No port. See [restore.md](restore.md). |

Networks: `edge` (Caddy only), `app` (internal, everything else) and `egress` (non-internal, `api`, `worker` and `backup` only, for outbound calls). Nothing else is published on the host.

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
   chmod 600 .env.prod   # the copy keeps the example's mode, which is world-readable
   openssl rand -base64 32   # one value each for POSTGRES_PASSWORD, RABBITMQ_PASSWORD, RUSTFS_ACCESS_KEY, RUSTFS_SECRET_KEY
   getent group emitkit-sandbox | cut -d: -f3   # the value for SANDBOX_GID (the example's 10010 is a sample)
   ```

   Also fill `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` from the GitHub OAuth app. Set `API_VAULT_ROLE_ID`, `WORKER_VAULT_ROLE_ID` and `BACKUP_VAULT_ROLE_ID` to `pending` for now: compose refuses an empty value, and step 6 replaces them. Compose refuses to start while any secret is empty, so a forgotten value cannot leave a stack running on an example password.

   The off-host backup target is a bucket on another service: fill `BACKUP_S3_ACCESS_KEY` and `BACKUP_S3_SECRET_KEY` (a key for that bucket only), and check the other `BACKUP_S3_*` values. Create the bucket first. It must be `https`, and the endpoint must not be empty. Backups and restores are in [restore.md](restore.md).

   `.env.prod`, `secrets/` and `backups/` are git-ignored. Keep them out of the repository.

   Memory: `API_MEMORY_LIMIT` (default `1g`) and `WORKER_MEMORY_LIMIT` (default `1536m`) cap each api and worker replica. The JVM sizes its heap to 75% of the cap. Two replicas of each take 5 GiB by default, so the host needs that plus headroom for Postgres, RabbitMQ, Redis, RustFS, Vault and Caddy. Without the caps, the JVMs could together claim far more than the host has, and the kernel could kill Vault, which then comes back sealed. Raise the caps only after watching `docker stats` under real load.

2. Start Vault. It comes up sealed, and `--wait` still passes because a sealed Vault is reachable:

   ```sh
   $COMPOSE up -d --wait vault
   ```

3. Initialize Vault once. Save the 5 unseal shares and the root token in separate offline places:

   ```sh
   $COMPOSE exec vault vault operator init -key-shares=5 -key-threshold=3
   ```

4. Unseal it with 3 of the 5 shares (see [Unseal](#unseal)).

5. Bootstrap Transit, AppRole and the policies, using the root token. Put the token in your shell without writing it to the history. This is bash (`read -s` is not POSIX):

   ```bash
   read -rs -p "Root token: " VAULT_TOKEN && echo && export VAULT_TOKEN
   ```

   The commands run inside the `vault` container, and `-e VAULT_TOKEN` passes the token from your shell:

   ```sh
   V="$COMPOSE exec -T -e VAULT_TOKEN vault vault"
   $V secrets enable transit
   $V auth enable approle
   $V write transit/keys/emitkit-secrets type=aes256-gcm96 derived=true exportable=false
   $V policy write emitkit-api - < vault/policies/emitkit-api.hcl
   $V policy write emitkit-worker - < vault/policies/emitkit-worker.hcl
   $V policy write emitkit-backup - < vault/policies/emitkit-backup.hcl
   $V write auth/approle/role/emitkit-api token_policies=emitkit-api token_ttl=1h
   $V write auth/approle/role/emitkit-worker token_policies=emitkit-worker token_ttl=1h
   $V write auth/approle/role/emitkit-backup token_policies=emitkit-backup token_ttl=1h
   ```

6. Put the role IDs in `.env.prod` (`API_VAULT_ROLE_ID`, `WORKER_VAULT_ROLE_ID`, `BACKUP_VAULT_ROLE_ID`):

   ```sh
   $V read -field=role_id auth/approle/role/emitkit-api/role-id
   $V read -field=role_id auth/approle/role/emitkit-worker/role-id
   $V read -field=role_id auth/approle/role/emitkit-backup/role-id
   ```

7. Write the secret IDs to host files. The containers read them as Docker secrets, so the files must end up readable by uid 10001. The files are created private, then handed to uid 10001 with mode 0400. The `umask 077` is in a subshell, so it does not carry into later steps. The `chmod` runs under sudo because the files are owned by 10001 after the `chown`:

   ```sh
   (
     umask 077
     mkdir -p secrets
     $V write -f -field=secret_id auth/approle/role/emitkit-api/secret-id > secrets/api-secret-id
     $V write -f -field=secret_id auth/approle/role/emitkit-worker/secret-id > secrets/worker-secret-id
     $V write -f -field=secret_id auth/approle/role/emitkit-backup/secret-id > secrets/backup-secret-id
     sudo chown 10001:10001 secrets/api-secret-id secrets/worker-secret-id
     sudo chmod 0400 secrets/api-secret-id secrets/worker-secret-id
     sudo chown 10001:10001 secrets/backup-secret-id
     sudo chmod 0400 secrets/backup-secret-id
   )
   ```

   Rotating a secret ID later needs the same `sudo` steps.

8. Revoke the root token. Use a fresh root token from `vault operator generate-root` for any later admin task (see [Upgrade](#upgrade), step 1, for the procedure):

   ```sh
   $V token revoke -self
   unset VAULT_TOKEN
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

11. Run one backup by hand. The bucket must already exist (the job does not create it). Check the `last-success` files:

    ```sh
    $COMPOSE exec -T -u backup backup /usr/local/bin/backup.sh now
    $COMPOSE exec -T -u backup backup sh -c 'ls /var/lib/backup/last-success-*'
    ```

## Unseal

Vault starts sealed after every restart of the container or the host, and after any recreate of the `vault` service. Unseal it with any 3 of the 5 shares:

Run `unseal` three times with no argument. Vault then asks for one share at a time with hidden input. Leave out `-T` so that the prompt has a terminal:

```sh
$COMPOSE exec vault vault operator unseal   # share 1 of 3
$COMPOSE exec vault vault operator unseal   # share 2 of 3
$COMPOSE exec vault vault operator unseal   # share 3 of 3
$COMPOSE exec -T vault vault status   # Sealed: false
```

Shares are typed at the prompt. They must never go on the command line, where `ps`, `docker inspect` and `docker events` can see them, and never into a file or the shell history. If secret-using endpoints still return `SECRETS_UNAVAILABLE` after unsealing, restart the apps: `$COMPOSE restart api worker`.

## After a reboot or a sandbox daemon restart

- Vault starts sealed after a host reboot. Unseal it as above.
- The worker bind-mounts the sandbox socket file. The mount keeps the old socket. If the `emitkit-sandbox` daemon restarts, or the host reboots, the socket is recreated and the worker keeps a dead socket, and each sandbox step fails. Run `$COMPOSE restart worker` after any sandbox daemon restart.
- After a reboot, check `$COMPOSE ps`. If the worker is not running, the daemon may not have created the socket when Docker started the container. Once the socket exists, run `$COMPOSE up -d worker`.

## Upgrade

1. Back up first. The dumps hold every user row and the Transit keyring, so they are readable by your user only. The `umask 077` is in a subshell, so it does not carry into the checkout in step 2:

   ```sh
   (
     umask 077
     mkdir -p backups
     $COMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > backups/emitkit-$(date +%F).sql
   )
   ```

   Also take a Raft snapshot. It needs an admin token, and First install step 8 revoked the root token. Get a temporary root token with `vault operator generate-root`. This runbook does not script that flow, and its exact flags were not checked against the pinned `hashicorp/vault:2.2.1` image. Follow Vault's `operator generate-root` documentation for that version, and enter the unseal shares and any one-time password at hidden prompts, not on the command line. Then store the token in your shell and take the snapshot:

   ```sh
   read -rs -p "Admin token: " VAULT_TOKEN && echo && export VAULT_TOKEN
   V="$COMPOSE exec -T -e VAULT_TOKEN vault vault"
   $V operator raft snapshot save /vault/file/pre-upgrade.snap
   $V token revoke -self
   unset VAULT_TOKEN
   ```

   Then copy the snapshot out and restrict it. This is in a subshell for the same reason as the dumps:

   ```sh
   (
     umask 077
     $COMPOSE cp vault:/vault/file/pre-upgrade.snap backups/
     chmod 600 backups/pre-upgrade.snap
   )
   ```

2. Check out the new release tag and rebuild:

   ```sh
   git fetch --tags && git checkout <tag>
   $COMPOSE up -d --wait --build
   $COMPOSE up -d --force-recreate caddy
   ```

   Compose recreates only the services whose config or image changed. An unchanged `vault` keeps running, so it stays unsealed. If `vault` was recreated, unseal it again.

   The Caddyfile is a single-file bind mount. `git checkout` replaces the file with a new one, so a running Caddy keeps the old copy and Compose does not see the change. The forced recreate of `caddy` loads the new Caddyfile. It runs after the build, so it also picks up a new `web` image.

3. Run the checks from step 10 of [First install](#first-install).

**Rollback** means checking out the previous tag and running `up -d --wait --build` again. There is no image registry in this stack, so the old images are rebuilt from source. Database migrations are not reversed automatically. If the new release migrated the schema, restore the Postgres backup from step 1 as well.

## Known gaps

- Traffic between the app containers and Vault, Postgres, RabbitMQ, Redis and RustFS is plain text on the `app` network. Vault has `tls_disable = true`. Redis has no password.
- `app` is `internal: true`. `api`, `worker` and `backup` also join `egress` (not internal) for outbound calls such as GitHub and the off-host target. Nothing on `egress` publishes a port. Anything that must not reach the internet belongs on `app` only.
- The worker's sandbox socket is root-equivalent on the host. See `docs/spikes/2026-gvisor-rootless.md`, "Security under B". Who may join the `emitkit-sandbox` group is still to be decided.
- Vault runs as a single node, unsealed by hand. High availability and auto-unseal are v4.
- Backups run off-host on a schedule, and restores are a manual procedure. Point-in-time recovery is not possible yet. See [restore.md](restore.md), Known gaps.
- The worker has no health endpoint, so it has no healthcheck. `up --wait` only waits for it to start.
- Caddy's health checks on the api replicas are passive. Caddy's active checks do not run for the Docker DNS upstreams, and Docker's healthcheck does not feed Caddy. A replica counts as failed only on a refused or failed connection, or on no response headers within 30 s (API routes only). One failure skips that replica for 30 s (`max_fails 1`, `fail_duration 30s`). Application responses, including 5xx, do not count, so a sealed Vault (503 `SECRETS_UNAVAILABLE`) does not take replicas out of rotation. Observed with one api replica paused (`docker pause`): the first request that Caddy sends to it takes about 30 s to fail, and it is then retried on the other replica and returns normally. Later requests in the 30 s window go straight to the healthy replica (about 20 ms). Once the 30 s window passes, the paused replica is tried again, so each window can cost one slow request while a replica stays hung. A request that was already sent is retried only for GET and HEAD, so a timed-out POST is not replayed (Caddy's documented default, stated in the Caddyfile). The SSE route `/api/v1/runs/*/logs/stream` has no header timeout, because a stream may send nothing before its first event.
