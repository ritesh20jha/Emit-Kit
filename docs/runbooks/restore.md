# Backups and restore (Docker Compose)

Runbook for the backups of the production stack in `deploy/compose/docker-compose.prod.yml`: what is backed up, where it goes, how to read the logs, and how to restore by hand. The quarterly restore drill, `scripts/restore-drill.sh`, runs this same procedure with throwaway stacks. Run the drill to prove the backups still restore. Run this runbook by hand when you restore for real.

## What is backed up

The `backup` service runs busybox crond. Times are UTC. Every job writes to the off-host target `BACKUP_S3_BUCKET` (see [deploy.md](deploy.md), First install, step 1).

| Data | How | When | Off-host prefix | Kept |
|---|---|---|---|---|
| Postgres | `pg_dump -Fc` of `POSTGRES_DB` | 02:00 daily | `pg/` | 7 days |
| Vault | Raft snapshot (`vault operator raft snapshot save`) with AppRole `emitkit-backup`, which can read snapshots and nothing else | every 6 h (00, 06, 12, 18) | `vault/` | 7 days |
| RustFS artifacts | `rclone sync` of `S3_BUCKET` to a mirror | 03:00 daily | `rustfs/` | mirror: follows the source, no history |
| Postgres WAL | `archive_command` copies each completed segment to a spool; the job moves it off-host | every 15 min | `wal/` | 7 days |
| Retention | `rclone delete --min-age 7d` on `pg/`, `vault/` and `wal/` | 04:00 daily | | |

Names start with a UTC timestamp (`20261111T020000Z.dump`), so the newest object is the last one in a sorted list. The Vault snapshot contains the Transit keyring and the AppRole secret IDs, and the Postgres dump contains the encrypted webhook secrets. The off-host target holds sensitive data. Give its key access to the one bucket only, and keep the bucket private.

## Checking the backups

Each successful step writes `/var/lib/backup/last-success-<step>`, containing the UTC time and the object name. Read them as the backup user (the files are private):

```sh
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
$COMPOSE exec -T -u backup backup sh -c 'for f in /var/lib/backup/last-success-*; do echo "$f: $(cat "$f")"; done'
```

The job log is in the container log, one line per step with a UTC timestamp:

```sh
$COMPOSE logs --tail 100 backup
```

A failed step logs `FAILED: <reason>` and stops. A step that has not succeeded for longer than its schedule means something is wrong. Check the log before you rely on the backups. The log file `/var/lib/backup/cron.log` grows by a few lines a day. Remove it if it grows too large, for example `$COMPOSE exec -T -u backup backup sh -c ': > /var/lib/backup/cron.log'`.

To run a step now, or all of them, as the backup user:

```sh
$COMPOSE exec -T -u backup backup /usr/local/bin/backup.sh now     # or: pg, vault, rustfs, wal, prune
```

## Prerequisites for a restore

- The unseal shares: 3 of the 5 shares from the original `vault operator init`, held offline. A snapshot restores the data but not the keys. The restored Vault is sealed under the original keys.
- The off-host credentials (`BACKUP_S3_*`) and the `.env.prod` of the host being restored. Keep `.env.prod` and the `secrets/` files in your offline store too.
- A host with Docker, the release tag checked out, and the SBX-01 sandbox socket set up (deploy.md, Before you start).
- An empty Vault data volume. A restore replaces all of Vault's data. Run it on a new host, or after removing the `vault-data` volume on the old one.

## Restore order

Postgres, then Vault, then RustFS, then api and worker. The api needs all three.

```sh
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
BACKUP="$COMPOSE run --rm --no-deps -T -u backup --entrypoint sh backup -c"
```

### 1. Postgres

This replaces the database contents, so use an empty database (a new host, or a volume that is removed first). Find the newest dump and restore it:

```sh
$COMPOSE up -d --wait postgres
$BACKUP 'rclone lsf "offhost:$BACKUP_S3_BUCKET/pg/" | sort | tail -n 1'      # prints the newest dump name, for example 20261111T020000Z.dump
# Replace <dump name> with the name printed above.
$BACKUP 'rclone cat "offhost:$BACKUP_S3_BUCKET/pg/<dump name>"' |
  $COMPOSE exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error'
```

### 2. Vault

Vault has to be initialized and unsealed before a snapshot can be restored into it. Use a new, empty Vault: initialize it with one throwaway key, restore the snapshot, then unseal with the original shares. The throwaway key and token stop working at the restore, so discard them after it.

```sh
$COMPOSE up -d --wait vault
$COMPOSE exec vault vault operator init -key-shares=1 -key-threshold=1   # note the key and the root token on screen
$COMPOSE exec vault vault operator unseal                                  # enter the throwaway key at the prompt
read -rs -p "Throwaway root token: " VAULT_TOKEN && echo && export VAULT_TOKEN
```

Copy the newest snapshot off-host into a private file, then restore it:

```sh
(
  umask 077
  mkdir -p backups
  # Replace <snapshot name> with the newest name from: $BACKUP 'rclone lsf "offhost:$BACKUP_S3_BUCKET/vault/" | sort | tail -n 1'
  $BACKUP 'rclone cat "offhost:$BACKUP_S3_BUCKET/vault/<snapshot name>"' > backups/restore.snap
)
$COMPOSE exec -T vault sh -c 'umask 077 && cat > /tmp/restore.snap' < backups/restore.snap
$COMPOSE exec -T -e VAULT_TOKEN vault vault operator raft snapshot restore -force /tmp/restore.snap
$COMPOSE exec -T vault rm -f /tmp/restore.snap
rm -f backups/restore.snap
unset VAULT_TOKEN
```

The restored Vault is sealed. Check that, then unseal with 3 of the **original** 5 shares (deploy.md, Unseal):

```sh
$COMPOSE exec -T vault vault status      # expect Sealed: true (exit code 2)
$COMPOSE exec vault vault operator unseal   # share 1 of 3, original key
$COMPOSE exec vault vault operator unseal   # share 2 of 3
$COMPOSE exec vault vault operator unseal   # share 3 of 3
$COMPOSE exec -T vault vault status      # Sealed: false
```

The AppRoles, policies and the Transit key are in the snapshot. The secret ID files in `deploy/compose/secrets/` still work, so you do not create new ones. If they are lost, create them as deploy.md, First install steps 6 and 7 say, with an admin token from `vault operator generate-root`.

### 3. RustFS

```sh
$COMPOSE up -d --wait rustfs-init
$BACKUP 'rclone sync "offhost:$BACKUP_S3_BUCKET/rustfs/" "rustfs:$S3_BUCKET"'
```

### 4. api and worker

```sh
$COMPOSE up -d --wait --build
$COMPOSE exec -T api curl -fsS "http://127.0.0.1:8081/internal/selftest/decrypt?projectId=<a project id>"   # prints ok
```

`ok` means that a stored webhook secret decrypts with the restored Vault and database. Then run the checks in deploy.md, First install, step 10.

## Restore drill

`scripts/restore-drill.sh` runs the whole procedure with throwaway stacks and only Docker. It seeds a source stack, backs it up off-host to a second RustFS, removes the source stack with its volumes, restores from the off-host data only, and checks the row count, the artifact and the selftest. It also checks that a sealed, restored Vault makes the selftest fail. CI runs it on every change to `deploy/`. Run it quarterly as well: `bash scripts/restore-drill.sh` from any directory.

## Known gaps

- **No point-in-time recovery.** The WAL is archived and moved off-host, but a WAL segment can only be replayed on top of a base backup. The base backup (`pg_basebackup`) is not implemented in this ticket, so the restore above gives the state at the newest dump: up to 24 hours old. Until base backups exist, the WAL is kept as a record and not used for recovery.
- **Vault snapshots are every 6 hours.** A restore loses at most 6 hours of Vault writes (new AppRole secret IDs, Transit key changes).
- **No encryption on the backup job.** Dumps and snapshots are uploaded as they are. The protection is the off-host bucket's access control. Client-side encryption is not implemented.
- **The RustFS mirror has no history.** A deletion on the source is deleted off-host at the next 03:00 sync. Restores from the mirror give the bucket as it was then.
- **Restores need people.** The unseal shares and an admin token for any later admin task are held offline. The procedure above is manual on purpose.
- **Off-host availability.** If the off-host target is down, the steps fail and the log says so. Nothing is queued.
