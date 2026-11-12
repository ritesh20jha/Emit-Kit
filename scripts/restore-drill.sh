#!/usr/bin/env bash
# Restore drill (OPS-02): back up a seeded source stack off-host, remove it, restore from off-host data only.
# Needs only Docker with Compose: bash scripts/restore-drill.sh
set -euo pipefail

DIR=$(cd "$(dirname "$0")/../deploy/compose" && pwd)
cd "$DIR"
umask 077
WORK=$(mktemp -d)
SECRETS=$WORK/secrets
ENV_FILE=$WORK/drill.env
NETWORK=emitkit-drill-offsite
OFF_BUCKET=backups
OBJECT_KEY=drill/seeded.txt
OBJECT_BODY=$(openssl rand -hex 16)
WEBHOOK_PLAINTEXT=$(openssl rand -hex 16)
SEEDED_PROJECTS=2
USER_ID=$(printf '01DRILLUSER%015d' 1)
ORG_ID=$(printf '01DRILLORG%016d' 1)
PROJECT_A=$(printf '01DRILLPROJ%015d' 1)
PROJECT_B=$(printf '01DRILLPROJ%015d' 2)
COMPOSE_ARGS=(-f docker-compose.prod.yml -f backup/docker-compose.drill.yml --env-file "$ENV_FILE")

fail() { echo "restore drill FAILED: $*" >&2; exit 1; }
src()   { docker compose -p emitkit-drill-src     "${COMPOSE_ARGS[@]}" "$@"; }
drill() { docker compose -p drill                 "${COMPOSE_ARGS[@]}" "$@"; }
off()   { docker compose -p emitkit-drill-offhost "${COMPOSE_ARGS[@]}" "$@"; }

cleanup() {
  status=$?
  trap - EXIT
  set +e
  if [ "$status" -ne 0 ]; then
    for stack in src drill off; do
      echo "--- compose logs: $stack ---" >&2
      "$stack" logs --tail 200 >&2
    done
  fi
  drill down -v --remove-orphans >/dev/null 2>&1
  src down -v --remove-orphans >/dev/null 2>&1
  off down -v --remove-orphans >/dev/null 2>&1
  # vault-init runs as root, so the files it writes are root-owned. Remove them through a container.
  if [ -d "$SECRETS" ]; then
    docker run --rm --network none -v "$SECRETS:/s" alpine sh -c 'rm -rf /s/* /s/.[!.]*' >/dev/null 2>&1
    rmdir "$SECRETS" 2>/dev/null
  fi
  docker network rm "$NETWORK" >/dev/null 2>&1
  rm -rf "$WORK" 2>/dev/null
  exit "$status"
}

# init_vault STACK initializes Vault with one throwaway key and unseals it. Sets INIT_UNSEAL and INIT_ROOT.
# The init output is parsed and never printed.
init_vault() {
  local json flat
  json=$("$1" exec -T vault vault operator init -key-shares=1 -key-threshold=1 -format=json) || fail "vault init ($1)"
  flat=$(printf '%s' "$json" | tr -d ' \n')
  INIT_UNSEAL=$(printf '%s' "$flat" | sed -n 's/.*"unseal_keys_b64":\["\([^"]*\)".*/\1/p')
  INIT_ROOT=$(printf '%s' "$flat" | sed -n 's/.*"root_token":"\([^"]*\)".*/\1/p')
  [ -n "$INIT_UNSEAL" ] && [ -n "$INIT_ROOT" ] || fail "could not read the vault init output ($1)"
  "$1" exec -T vault vault operator unseal "$INIT_UNSEAL" >/dev/null || fail "vault unseal ($1)"
}

# drill_sh runs a shell command in a one-off backup container of the drill stack.
drill_sh() {
  drill run --rm --no-deps -T -u backup --entrypoint sh backup -c "$1"
}

# latest_offsite PREFIX prints the newest object name under PREFIX/.
latest_offsite() {
  drill_sh "rclone lsf \"offhost:\$BACKUP_S3_BUCKET/$1/\" | sort | tail -n 1"
}

# seed_data writes an owner, an org, two projects and an artifact. Project A's webhook secret is Transit-encrypted
# with the api's SecretContext (table:column:rowId, base64).
seed_data() {
  local ctx pt ct sql
  ctx=$(printf '%s' "projects:webhook_secret_enc:$PROJECT_A" | base64 | tr -d '\n')
  pt=$(printf '%s' "$WEBHOOK_PLAINTEXT" | base64 | tr -d '\n')
  export VAULT_TOKEN=$SRC_ROOT
  ct=$(src exec -T -e VAULT_TOKEN vault vault write -field=ciphertext transit/encrypt/emitkit-secrets \
    plaintext="$pt" context="$ctx") || fail "transit encrypt"
  unset VAULT_TOKEN
  case "$ct" in vault:v1:*) ;; *) fail "unexpected transit ciphertext format" ;; esac
  sql=$(cat <<SQL
insert into users (id, github_id, login, avatar_url, created_at, updated_at)
values ('$USER_ID', 9001, 'drill-owner', 'https://example.invalid/avatar.png', now(), now());
insert into organizations (id, vcs_provider, vcs_org_id, slug, name, is_personal, owner_user_id, created_at)
values ('$ORG_ID', 'github', '9002', 'drill-org', 'Drill org', false, '$USER_ID', now());
insert into projects (id, org_id, repo_full_name, spec_path, default_branch, output_mode,
                      webhook_secret_enc, created_by, created_at, updated_at)
values ('$PROJECT_A', '$ORG_ID', 'drill-org/alpha', 'emitkit.yaml', 'main', 'APPEND',
        '$ct', '$USER_ID', now(), now()),
       ('$PROJECT_B', '$ORG_ID', 'drill-org/beta', 'emitkit.yaml', 'main', 'APPEND',
        NULL, '$USER_ID', now(), now());
SQL
  )
  printf '%s\n' "$sql" |
    src exec -T postgres sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -' ||
    fail "seed rows"
  printf '%s' "$OBJECT_BODY" |
    src run --rm --no-deps -T --entrypoint sh rustfs-init \
      -c 'aws --endpoint-url "$S3_ENDPOINT" s3 cp - s3://"$S3_BUCKET"/drill/seeded.txt' >/dev/null ||
    fail "seed artifact"
}

# archived prints the source's pg_stat_archiver.archived_count.
archived() {
  src exec -T postgres sh -c 'exec psql -X -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select archived_count from pg_stat_archiver"' |
    tr -d '[:space:]'
}

# wal_drill archives one WAL segment off-host from the source (min age 0s) and asserts it arrived and the spool drained.
wal_drill() {
  local before now_count left
  before=$(archived) || fail "read pg_stat_archiver"
  src exec -T postgres sh -c 'exec psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select pg_switch_wal()"' >/dev/null ||
    fail "pg_switch_wal"
  for _ in $(seq 60); do
    now_count=$(archived)
    [ "$now_count" -gt "$before" ] && break
    sleep 1
  done
  [ "${now_count:-0}" -gt "$before" ] || fail "postgres did not archive the switched WAL segment"
  src exec -T -u backup -e WAL_MIN_AGE=0s backup /usr/local/bin/backup.sh wal || fail "backup.sh wal"
  moved=$(src exec -T -u backup backup sh -c 'rclone lsf "offhost:$BACKUP_S3_BUCKET/wal/"') || fail "list off-host WAL"
  [ -n "$moved" ] || fail "no WAL segment off-host after backup.sh wal"
  left=$(src exec -T -u backup backup ls -A /wal-archive) || fail "list the WAL spool"
  [ -z "$left" ] || fail "WAL spool not drained: $left"
}

restore_vault() {
  local snap status
  drill up -d --wait vault
  init_vault drill
  snap=$(latest_offsite vault)
  [ -n "$snap" ] || fail "no vault snapshot off-host"
  drill_sh "rclone cat \"offhost:\$BACKUP_S3_BUCKET/vault/$snap\"" |
    drill exec -T vault sh -c 'umask 077 && cat > /tmp/restore.snap' || fail "copy vault snapshot"
  export VAULT_TOKEN=$INIT_ROOT
  drill exec -T -e VAULT_TOKEN vault sh -c \
    'vault operator raft snapshot restore -force /tmp/restore.snap; rc=$?; rm -f /tmp/restore.snap; exit $rc' ||
    fail "vault operator raft snapshot restore"
  unset VAULT_TOKEN
  # A restored Vault seals itself: it reloads the root key from the snapshot, which the drill's key cannot
  # decrypt, and seals within seconds. Wait for that, bounded, and require sealed (exit 2 from vault status).
  status=0
  for _ in $(seq 60); do
    status=0
    drill exec -T vault vault status >/dev/null 2>&1 || status=$?
    [ "$status" = 2 ] && break
    sleep 1
  done
  [ "$status" = 2 ] || fail "restored vault is not sealed after 60 s (vault status exit $status)"
  if drill exec -T vault vault operator unseal "$INIT_UNSEAL" >/dev/null 2>&1; then
    fail "the drill's own unseal key unsealed the restored vault"
  fi
}

# selftest prints the api's answer for the seeded project. It fails (non-zero) on any non-2xx answer.
selftest() {
  drill exec -T api curl -fsS "http://127.0.0.1:8081/internal/selftest/decrypt?projectId=$PROJECT_A"
}

mkdir -p "$SECRETS"
trap cleanup EXIT

# Generated values for every empty key, then the drill's overrides (a later duplicate wins in a compose env file).
bash gen-env.sh > "$ENV_FILE"
OFF_KEY=$(openssl rand -hex 16)
OFF_SECRET=$(openssl rand -hex 24)
cat >> "$ENV_FILE" <<ENV
OFFHOST_RUSTFS_ACCESS_KEY=$OFF_KEY
OFFHOST_RUSTFS_SECRET_KEY=$OFF_SECRET
BACKUP_S3_ENDPOINT=http://offhost:9000
BACKUP_S3_BUCKET=$OFF_BUCKET
BACKUP_S3_REGION=us-east-1
BACKUP_S3_ACCESS_KEY=$OFF_KEY
BACKUP_S3_SECRET_KEY=$OFF_SECRET
DRILL_SECRETS_DIR=$SECRETS
API_VAULT_SECRET_ID_FILE=$SECRETS/api-secret-id
WORKER_VAULT_SECRET_ID_FILE=$SECRETS/worker-secret-id
BACKUP_VAULT_SECRET_ID_FILE=$SECRETS/backup-secret-id
ENV

docker network create "$NETWORK" >/dev/null
echo "== off-host target"
off up -d --wait offhost

echo "== source stack: vault bootstrap"
src up -d --wait vault
init_vault src
SRC_UNSEAL=$INIT_UNSEAL
SRC_ROOT=$INIT_ROOT
export DRILL_VAULT_ROOT_TOKEN=$SRC_ROOT
src up -d --wait vault-init
API_VAULT_ROLE_ID=$(cat "$SECRETS/api-role-id") || fail "api role id"
BACKUP_VAULT_ROLE_ID=$(cat "$SECRETS/backup-role-id") || fail "backup role id"
export API_VAULT_ROLE_ID BACKUP_VAULT_ROLE_ID

echo "== source stack: api (creates the schema), backup"
src up -d --wait --build api backup
src run --rm --no-deps -T -u backup --entrypoint rclone backup mkdir "offhost,no_check_bucket=false:$OFF_BUCKET"
seed_data
src exec -T -u backup backup /usr/local/bin/backup.sh now
# The pg job exactly as crond runs it: the command text from the crontab, an empty environment, the env file it sources.
cron_pg=$(sed -E 's/^([^ ]+ +){5}//' backup/crontab | grep 'backup.sh pg ')
dumps() { src exec -T -u backup backup sh -c 'rclone lsf "offhost:$BACKUP_S3_BUCKET/pg/"' | wc -l; }
before=$(dumps)
src exec -T -u backup backup env -i /bin/sh -c "$cron_pg" || fail "the pg cron line failed"
[ "$(dumps)" -gt "$before" ] || fail "the pg cron line added no dump off-host"
echo "== source stack: WAL segment off-host"
wal_drill
src down -v --remove-orphans
echo "== source stack removed with its volumes; only off-host data is left"

echo "== drill stack: restore"
drill up -d --wait postgres rustfs-init
name=$(latest_offsite pg)
[ -n "$name" ] || fail "no pg dump off-host"
drill_sh "rclone cat \"offhost:\$BACKUP_S3_BUCKET/pg/$name\"" |
  drill exec -T postgres sh -c \
    'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error' ||
  fail "pg_restore"
restore_vault
drill_sh 'rclone sync "offhost:$BACKUP_S3_BUCKET/rustfs/" "rustfs:$S3_BUCKET"' || fail "rustfs sync from off-host"
drill up -d --wait api

if selftest >/dev/null 2>&1; then
  fail "selftest answered ok while the restored vault is still sealed"
fi
drill exec -T vault vault operator unseal "$SRC_UNSEAL" >/dev/null || fail "unseal the restored vault with the source key"

count=$(drill exec -T postgres sh -c 'exec psql -X -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) from projects"') ||
  fail "count projects"
count=$(printf '%s' "$count" | tr -d '[:space:]')
[ "$count" = "$SEEDED_PROJECTS" ] || fail "projects after restore: $count, want $SEEDED_PROJECTS"

got=$(drill run --rm --no-deps -T --entrypoint sh rustfs-init \
  -c 'aws --endpoint-url "$S3_ENDPOINT" s3 cp s3://"$S3_BUCKET"/drill/seeded.txt -') || fail "read artifact after restore"
[ "$got" = "$OBJECT_BODY" ] || fail "artifact content differs after restore"

got=$(selftest) || fail "transit decrypt after restore (selftest did not answer 200)"
[ "$got" = ok ] || fail "transit decrypt after restore (selftest body is not ok)"

echo "restore ok"
