# Backups and restore (Docker Compose)

Runbook for the backups of the production stack (`deploy/compose/docker-compose.prod.yml`): what is backed up, how to read the logs, and how to restore by hand. `scripts/restore-drill.sh` runs this same procedure with throwaway stacks; run it quarterly, and run this runbook for a real restore. Commands are for bash.

## What is backed up

The `backup` service runs busybox crond. Times are UTC. Every job writes to the off-host bucket `BACKUP_S3_BUCKET` (see [deploy.md](deploy.md), First install, step 1).

| Data | How | When | Off-host prefix | Kept |
|---|---|---|---|---|
| Postgres | `pg_dump -Fc` of `POSTGRES_DB` | 02:00 daily | `pg/` | 7 days |
| Vault | Raft snapshot with AppRole `emitkit-backup` (read on snapshots only); the token is revoked after the snapshot | every 6 h (00, 06, 12, 18) | `vault/` | 7 days |
| RustFS artifacts | `rclone sync` of `S3_BUCKET` to a mirror; overwritten or deleted objects go to `rustfs-deleted/<run>` first | 03:00 daily | `rustfs/`, `rustfs-deleted/` | mirror follows the source; `rustfs-deleted/` 7 days |
| Postgres WAL | `archive_command` copies each completed segment to the spool `wal-archive`; the job moves it off-host | every 15 min (`archive_timeout=15min` forces a segment switch) | `wal/` | 7 days |
| Retention | `rclone delete --min-age 7d` on `pg/`, `vault/`, `wal/`; age by run name on `rustfs-deleted/` | 04:00 daily | | |

Object names start with a UTC timestamp (`20261111T020000Z.dump`), so the newest object is the last one in a sorted list. The Vault snapshot contains the Transit keyring and the AppRole secret IDs. The Postgres dump contains the encrypted webhook secrets. Treat the off-host bucket as sensitive: give its key access to that one bucket only.

## Checking the backups

Each successful step writes `/var/lib/backup/last-success-<step>` (UTC time and object name). The files are private to the backup user:

```bash
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
$COMPOSE exec -T -u backup backup sh -c 'for f in /var/lib/backup/last-success-*; do echo "$f: $(cat "$f")"; done'
$COMPOSE logs --tail 100 backup     # one line per step, with a UTC timestamp; FAILED: <reason> on error
```

A step whose `last-success` is older than its schedule means something is wrong. To run a step now, or all of them:

```bash
$COMPOSE exec -T -u backup backup /usr/local/bin/backup.sh now     # or pg, vault, rustfs, wal, prune
```

## Prerequisites

- The unseal shares: 3 of the 5 from the original `vault operator init`, held offline. A snapshot restores the data but not the keys. The restored Vault is sealed under the original keys.
- The off-host credentials (`BACKUP_S3_*`), and the `.env.prod` and `secrets/` of the host being restored, from your offline store.
- A host with Docker, the release tag checked out, and the SBX-01 sandbox socket set up (deploy.md, Before you start).
- An empty Vault data volume. A restore replaces all of Vault's data. Use a new host, or remove the `vault-data` volume on the old one.

## Restore order

Postgres, then Vault, then RustFS, then api and worker. The api needs all three.

```bash
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
backup_sh() { $COMPOSE run --rm --no-deps -T -u backup --entrypoint sh backup -c "$1"; }
```

### 1. Postgres

This replaces the database contents, so use an empty database (a new host, or a removed volume):

```bash
$COMPOSE up -d --wait postgres
PG_DUMP=$(backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/pg/" | sort | tail -n 1')
echo "restoring $PG_DUMP"
backup_sh "rclone cat \"offhost:\$BACKUP_S3_BUCKET/pg/$PG_DUMP\"" |
  $COMPOSE exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error'
```

### 2. Vault

A snapshot can only be restored into a Vault that is initialized and unsealed. Initialize the empty Vault with one throwaway key, restore, and then unseal with the original shares. The throwaway key and token stop working at the restore, so discard them afterwards.

```bash
$COMPOSE up -d --wait vault
$COMPOSE exec vault vault operator init -key-shares=1 -key-threshold=1   # note the key and root token shown
$COMPOSE exec vault vault operator unseal                                # enter the throwaway key at the prompt
read -rs -p "Throwaway root token: " VAULT_TOKEN && echo && export VAULT_TOKEN

VAULT_SNAP=$(backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/vault/" | sort | tail -n 1')
echo "restoring $VAULT_SNAP"
(
  umask 077
  mkdir -p backups
  backup_sh "rclone cat \"offhost:\$BACKUP_S3_BUCKET/vault/$VAULT_SNAP\"" > backups/restore.snap
)
$COMPOSE exec -T vault sh -c 'umask 077 && cat > /tmp/restore.snap' < backups/restore.snap
$COMPOSE exec -T -e VAULT_TOKEN vault vault operator raft snapshot restore -force /tmp/restore.snap
$COMPOSE exec -T vault rm -f /tmp/restore.snap
rm -f backups/restore.snap
unset VAULT_TOKEN
```

After a restore, Vault seals itself within seconds, because the snapshot's root key cannot be decrypted with the throwaway key. Check that it is sealed (`vault status` exits 2), then unseal with 3 of the **original** 5 shares (deploy.md, Unseal):

```bash
$COMPOSE exec -T vault vault status        # expect Sealed: true (exit code 2); if it still says false, wait and check again
$COMPOSE exec vault vault operator unseal  # share 1 of 3, original key
$COMPOSE exec vault vault operator unseal  # share 2 of 3
$COMPOSE exec vault vault operator unseal  # share 3 of 3
$COMPOSE exec -T vault vault status        # Sealed: false
```

The AppRoles, policies and the Transit key are in the snapshot, so the secret ID files in `deploy/compose/secrets/` still work. If they are lost, create new ones as deploy.md, First install steps 6 and 7 describe, with an admin token from `vault operator generate-root`.

### 3. RustFS

```bash
$COMPOSE up -d --wait rustfs-init
backup_sh 'rclone sync "offhost:$BACKUP_S3_BUCKET/rustfs/" "rustfs:$S3_BUCKET"'
```

### 4. api and worker

```bash
$COMPOSE up -d --wait --build
PROJECT_ID=$($COMPOSE exec -T postgres sh -c 'exec psql -X -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id from projects order by created_at limit 1"' | tr -d '[:space:]')
$COMPOSE exec -T api curl -fsS "http://127.0.0.1:8081/internal/selftest/decrypt?projectId=$PROJECT_ID"   # prints ok
```

`ok` means a stored webhook secret decrypts with the restored Vault and database. Then run the checks in deploy.md, First install, step 10.

## Known gaps

- **No point-in-time recovery.** WAL is archived and moved off-host, but replaying it needs a base backup (`pg_basebackup`), which this ticket does not implement. A restore gives the newest dump, up to 24 hours old.
- **Vault loses up to 6 hours.** Snapshots are every 6 hours. Restores are manual, and they need the unseal shares and an admin token.
- **The backup job does not encrypt.** Dumps and snapshots are uploaded as they are. The protection is the off-host bucket's access control.
- **The WAL spool grows without a limit when backups stop.** If the off-host target is down or the backup container is stopped, `wal-archive` grows, nothing alerts, and a full disk eventually stops Postgres. Check `last-success-wal` and the spool size (`$COMPOSE exec -T postgres du -sh /wal-archive`).
