#!/bin/sh
# Off-host backups for the production stack: Postgres dumps, Raft snapshots of Vault, a mirror of the RustFS
# artifacts bucket, WAL segments, and retention. Runs in the backup container (see Dockerfile), as uid 10001.
#
#   backup.sh pg | vault | rustfs | wal | prune   one step
#   backup.sh now                                  pg, vault, rustfs, wal and prune, in that order
#   backup.sh start                                container entrypoint: writes the job environment, runs crond
#
# Remotes are configured by RCLONE_CONFIG_* in the container environment (docker-compose.prod.yml). Each step
# logs one line to stdout with a UTC timestamp, and writes /var/lib/backup/last-success-<step> when it succeeds.
# Nothing here prints a token, a secret id or a key.
set -eu

STATE=/var/lib/backup
WORK=$STATE/work
ENV_FILE=/run/backup/env
LOG=$STATE/cron.log

# The dump and the snapshot are sensitive: files are created private and removed by a trap.
umask 077

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "FAILED: $*" >&2; exit 1; }
stamp() { date -u +%Y%m%dT%H%M%SZ; }

# mark STEP [OBJECT] records a successful step.
mark() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${2:-}" > "$STATE/last-success-$1"; }

step_pg() {
  ts=$(stamp)
  dump=$WORK/pg-$ts.dump
  trap 'rm -f "$dump"' EXIT
  log "pg: dumping ${POSTGRES_DB:?}"
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
  unset token
  rclone copyto "$snap" "offhost:$BACKUP_S3_BUCKET/vault/$ts.snap" || fail "upload of the vault snapshot"
  log "vault: uploaded $ts.snap"
  mark vault "$ts.snap"
}

step_rustfs() {
  rclone sync "rustfs:$S3_BUCKET" "offhost:$BACKUP_S3_BUCKET/rustfs/" || fail "rustfs mirror"
  log "rustfs: mirrored $S3_BUCKET"
  mark rustfs
}

step_wal() {
  # --min-age keeps segments postgres may still be writing; *.partial is a copy in progress (see postgresql.conf).
  rclone move /wal-archive "offhost:$BACKUP_S3_BUCKET/wal/" --min-age 1m --exclude '*.partial' || fail "wal move"
  log "wal: moved archived segments"
  mark wal
}

step_prune() {
  # Seven days for the dumps, snapshots and WAL. The rustfs mirror is not pruned: it follows the source.
  for kind in pg vault wal; do
    rclone delete "offhost:$BACKUP_S3_BUCKET/$kind/" --min-age 7d || fail "prune $kind"
  done
  log "prune: removed objects older than 7 days"
  mark prune
}

step_now() {
  for step in pg vault rustfs wal prune; do
    "$0" "$step" || exit 1
  done
}

# start runs as root (the container default). Cron jobs do not inherit the container environment, so the job
# environment goes to a file that the crontab sources. Root writes it into the setgid /run/backup, which gives
# the file group backup, so the job user can read it.
start() {
  umask 027
  {
    printf "export PATH='%s'\n" "/usr/local/bin:/usr/bin:/bin"
    env | grep -E '^(BACKUP_|RCLONE_|RUSTFS_|POSTGRES_|S3_|VAULT_|PGHOST=|PGUSER=|PGPASSWORD=)' |
      while IFS= read -r line; do
        name=${line%%=*}
        value=${line#*=}
        printf "export %s='%s'\n" "$name" "$(printf '%s' "$value" | sed "s/'/'\\\\''/g")"
      done
  } > "$ENV_FILE.tmp"
  mv "$ENV_FILE.tmp" "$ENV_FILE"
  umask 077
  log "start: job environment written; crond running (schedule in /var/spool/cron/crontabs/backup)"
  # Stream the job log to the container log. tail waits for the file: the first job creates it, as user backup.
  tail -n 0 -F "$LOG" 2>/dev/null &
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
