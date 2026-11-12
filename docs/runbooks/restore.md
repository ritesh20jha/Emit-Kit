# Backups and restore (Docker Compose)

Backups and restores of the production stack. `scripts/restore-drill.sh` runs this procedure with throwaway stacks, quarterly. Commands are for bash.

## What is backed up

The `backup` service runs crond, in UTC, to the off-host bucket `BACKUP_S3_BUCKET` (deploy.md, First install, step 1).

| Data | How | When | Off-host prefix | Kept |
|---|---|---|---|---|
| Postgres | `pg_dump -Fc` of `POSTGRES_DB` | 02:00 daily | `pg/` | 7 days |
| Vault | Raft snapshot with AppRole `emitkit-backup` (read on snapshots only); the token is revoked after the snapshot | every 6 h (00, 06, 12, 18) | `vault/` | 7 days |
| RustFS artifacts | `rclone sync` of `S3_BUCKET` to a mirror; overwritten or deleted objects go to `rustfs-deleted/<run>` first | 03:30 daily | `rustfs/`, `rustfs-deleted/` | mirror follows the source; `rustfs-deleted/` 7 days |
| Postgres WAL | `archive_command` copies each completed segment to the spool `wal-archive`; the job moves it off-host | every 15 min; `archive_timeout=1h` forces a segment switch | `wal/` | 7 days |
| Retention | 7 days; a kind is pruned only while its own `last-success-*` marker is under 48 h old | 04:00 daily | | |

Object names start with a UTC timestamp, so the newest is last in a sorted list. The Vault snapshot is barrier-encrypted and needs the unseal shares. The Postgres dump is readable as it is. The off-host key can delete every backup, so enable versioning or object lock.

## Checking the backups

Each successful step writes `/var/lib/backup/last-success-<step>` (UTC time and object name). The files are private to the backup user:

```bash
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
$COMPOSE exec -T -u backup backup sh -c 'for f in /var/lib/backup/last-success-*; do echo "$f: $(cat "$f")"; done'
$COMPOSE logs --tail 100 backup     # one line per step, with a UTC timestamp; FAILED: <reason> on error
```

To run one step, or all of them (`now`): `$COMPOSE exec -T -u backup backup /usr/local/bin/backup.sh now`. A `last-success` older than its schedule needs a look.

## Prerequisites

- The unseal shares: 3 of the 5 from the original `vault operator init`, held offline. A snapshot restores the data but not the keys. The restored Vault is sealed under the original keys.
- The off-host credentials (`BACKUP_S3_*`), and the `.env.prod` and `secrets/` of the host, from your offline store. `BACKUP_S3_ENDPOINT` must be `https`.
- A host with Docker, the release tag checked out, and the SBX-01 sandbox socket set up (deploy.md, Before you start).
- An empty Vault data volume. A restore replaces all of Vault's data. Use a new host, or remove the `vault-data` volume on the old one.

## Restore order

Freeze the backups, restore Postgres, Vault, RustFS and then api and worker, and resume the backups last.

```bash
cd deploy/compose
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env.prod"
backup_sh() { $COMPOSE run --rm --no-deps -T -u backup --entrypoint sh backup -c "$1"; }
```

### 0. Freeze the backups

```bash
$COMPOSE stop backup   # keep it stopped until step 5. After an incident, a sync of an empty RustFS would move the mirror into rustfs-deleted/
```

### 1. Postgres

This replaces the database, so use an empty one. Take the newest dump. If `pg_restore` fails on it, list the last three (`backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/pg/" | sort | tail -n 3'`) and take the one before.

```bash
$COMPOSE up -d --wait postgres
PG_DUMP=$(backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/pg/" | sort | tail -n 1')
echo "restoring $PG_DUMP"
backup_sh "rclone cat \"offhost:\$BACKUP_S3_BUCKET/pg/$PG_DUMP\"" |
  $COMPOSE exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error'
```

### 2. Vault

A snapshot restores only into an initialized, unsealed Vault. Use a throwaway key and token, then discard them: they stop working at the restore.

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

The AppRoles and the Transit key are in the snapshot, so the files in `deploy/compose/secrets/` still work. If lost, recreate them per deploy.md, First install steps 6 and 7.

### 3. RustFS

```bash
$COMPOSE up -d --wait rustfs-init
backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/rustfs/" | head -n 20'   # what the mirror holds now
```

After an incident, the run that matters is the `rustfs-deleted/<run>` created at or after the incident: a sync of an emptied RustFS moved the lost objects into it. List the runs with `backup_sh 'rclone lsf "offhost:$BACKUP_S3_BUCKET/rustfs-deleted/" --dirs-only | sort'`, and list the chosen run's contents before copying. Copying a run over the mirror can overwrite newer objects, so check the listing first. Then copy the mirror, and that run over it.

```bash
RUN=20261110T033000Z   # replace with the run created at or after the incident
backup_sh 'rclone copy "offhost:$BACKUP_S3_BUCKET/rustfs/" "rustfs:$S3_BUCKET"'
backup_sh "rclone copy \"offhost:\$BACKUP_S3_BUCKET/rustfs-deleted/$RUN\" \"rustfs:\$S3_BUCKET\""
```

### 4. api and worker

```bash
# Name the services: backup stays stopped until step 5, and a bare `up` would restart it.
$COMPOSE up -d --wait --build caddy api worker
PROJECT_ID=$($COMPOSE exec -T postgres sh -c 'exec psql -X -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id from projects where webhook_secret_enc is not null order by created_at limit 1"' | tr -d '[:space:]')
if [ -n "$PROJECT_ID" ]; then
  $COMPOSE exec -T api curl -fsS "http://127.0.0.1:8081/internal/selftest/decrypt?projectId=$PROJECT_ID"   # prints ok
else
  echo "no project has a webhook secret: nothing to prove with; check the row counts and the artifacts instead"
fi
```

`ok` means a stored webhook secret decrypts with the restored Vault and database. An empty `PROJECT_ID` is not a Vault fault: check the counts instead.

### 5. Resume the backups

Only after the checks above pass:

```bash
$COMPOSE start backup
```

## Known gaps

- **No point-in-time recovery.** WAL needs a base backup (`pg_basebackup`) to replay; not implemented here. A restore gives the newest dump, up to 24 hours old.
- **Vault loses up to 6 hours**, and restores need the unseal shares and an admin token.
- **No encryption by the backup job.** Protection is the bucket's access control, `https` and versioning or lock.
- **The WAL spool grows without a limit when backups stop.** If the off-host target is down, the backup container is stopped, or archiving fails, `wal-archive` and Postgres's own `pg_wal` grow, nothing alerts, and a full disk eventually stops Postgres. Check `last-success-wal` and the sizes (`$COMPOSE exec -T postgres du -sh /wal-archive /var/lib/postgresql`).
