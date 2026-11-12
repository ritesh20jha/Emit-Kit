#!/bin/sh
# Off-host backups: Postgres dumps, Raft snapshots, the RustFS artifacts mirror, WAL segments, retention.
# Runs in the backup container as uid 10001. Usage: backup.sh pg|vault|rustfs|wal|prune|now, or start (entrypoint:
# writes the job environment for cron, then runs crond). Remotes come from RCLONE_CONFIG_* (docker-compose.prod.yml).
set -eu

STATE=/var/lib/backup
WORK=$STATE/work
ENV_FILE=/run/backup/env
umask 077 # dumps and snapshots are sensitive: created private, removed by a trap

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "FAILED: $*" >&2; exit 1; }
stamp() { date -u +%Y%m%dT%H%M%SZ; }
mark() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${2:-}" > "$STATE/last-success-$1"; }

step_pg() {
  ts=$(stamp)
  dump=$WORK/pg-$ts.dump
  trap 'rm -f "$dump"' EXIT
  pg_dump -Fc -f "$dump" "$POSTGRES_DB" || fail "pg_dump"
  rclone copyto "$dump" "offhost:$BACKUP_S3_BUCKET/pg/$ts.dump" || fail "upload of the pg dump"
  log "pg: uploaded $ts.dump"
  mark pg "$ts.dump"
}

step_vault() {
  ts=$(stamp)
  snap=$WORK/vault-$ts.snap
  trap 'rm -f "$snap"' EXIT
  : "${BACKUP_VAULT_ROLE_ID:?}" "${BACKUP_VAULT_SECRET_ID_FILE:?}"
  [ -r "$BACKUP_VAULT_SECRET_ID_FILE" ] || fail "AppRole secret id is not readable: $BACKUP_VAULT_SECRET_ID_FILE"
  # A sealed or unreachable Vault fails here, with a message, instead of inside the snapshot call.
  rc=0
  vault status >/dev/null 2>&1 || rc=$?
  case $rc in
    0) ;;
    2) fail "vault is sealed: unseal it (docs/runbooks/deploy.md, Unseal), then run this step again" ;;
    *) fail "vault is not reachable at ${VAULT_ADDR:-unset} (vault status exit $rc)" ;;
  esac
  token=$(vault write -field=token auth/approle/login role_id="$BACKUP_VAULT_ROLE_ID" \
    secret_id=@"$BACKUP_VAULT_SECRET_ID_FILE") || fail "AppRole login for emitkit-backup"
  VAULT_TOKEN=$token vault operator raft snapshot save "$snap" || fail "raft snapshot save"
  # Best effort: the token is not needed after the snapshot. Its output is discarded, so it is never printed.
  VAULT_TOKEN=$token vault token revoke -self >/dev/null 2>&1 || true
  unset token
  rclone copyto "$snap" "offhost:$BACKUP_S3_BUCKET/vault/$ts.snap" || fail "upload of the vault snapshot"
  log "vault: uploaded $ts.snap"
  mark vault "$ts.snap"
}

step_rustfs() {
  # Objects that are overwritten or deleted at the source are kept under rustfs-deleted/<run>: a lost RustFS volume
  # must not erase the only off-host copy. Pruned by name, see step_prune.
  rclone sync "rustfs:$S3_BUCKET" "offhost:$BACKUP_S3_BUCKET/rustfs/" \
    --backup-dir "offhost:$BACKUP_S3_BUCKET/rustfs-deleted/$(stamp)" || fail "rustfs mirror"
  log "rustfs: mirrored $S3_BUCKET"
  mark rustfs
}

step_wal() {
  # WAL_MIN_AGE is how old a segment must be before it moves. The drill sets it to 0s; cron uses the default.
  rclone move /wal-archive "offhost:$BACKUP_S3_BUCKET/wal/" --min-age "${WAL_MIN_AGE:-1m}" --exclude '*.partial' ||
    fail "wal move"
  log "wal: moved archived segments"
  mark wal
}

step_prune() {
  # Seven days. rustfs/ (the mirror) is not pruned. rustfs-deleted/ is aged by its run name: a moved object may keep
  # its original modification time, which can be older than the copy.
  for kind in pg vault wal; do
    rclone delete "offhost:$BACKUP_S3_BUCKET/$kind/" --min-age 7d || fail "prune $kind"
  done
  cutoff=$(date -u -d "@$(( $(date -u +%s) - 604800 ))" +%Y%m%dT%H%M%SZ)
  runs=$(rclone lsf "offhost:$BACKUP_S3_BUCKET/rustfs-deleted/" --dirs-only) || fail "list rustfs-deleted"
  for run in $(printf '%s\n' "$runs" | awk -v c="$cutoff" '{ n = $0; sub(/\/$/, "", n); if (n < c) print n }'); do
    rclone purge "offhost:$BACKUP_S3_BUCKET/rustfs-deleted/$run" || fail "prune rustfs-deleted $run"
  done
  log "prune: removed objects older than 7 days"
  mark prune
}

step_now() {
  for step in pg vault rustfs wal prune; do
    "$0" "$step" || exit 1
  done
}

# start runs as root (the container default). Cron jobs do not inherit the container environment: the job
# environment goes to a file that the crontab sources. Root writes it into the setgid /run/backup, so the file is
# group backup and the job user can read it.
start() {
  umask 027
  {
    echo "export PATH='/usr/local/bin:/usr/bin:/bin'"
    export -p | grep -E "^export (BACKUP_|RCLONE_|RUSTFS_|POSTGRES_|S3_|VAULT_|PGHOST=|PGUSER=|PGPASSWORD=)"
  } > "$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
  umask 077
  log "start: job environment written; crond running (schedule in /var/spool/cron/crontabs/backup)"
  tail -n 0 -F "$STATE/cron.log" 2>/dev/null & # the first job creates the log as user backup
  exec crond -f -c /var/spool/cron/crontabs -L /dev/stderr
}

case "${1:-}" in
  pg|vault|rustfs|wal|prune|now)
    mkdir -p "$WORK"
    "step_$1"
    ;;
  start)
    start
    ;;
  *)
    echo "usage: backup.sh pg|vault|rustfs|wal|prune|now|start" >&2
    exit 2
    ;;
esac
