#!/usr/bin/env bash
# Checks the local Vault bootstrap: AppRole logins, Transit round-trip and least-privilege policies.
# Uses a host `vault` CLI if present, otherwise the CLI inside the compose `vault` service.
set -eu

DIR=$(cd "$(dirname "$0")" && pwd)
SECRETS=$DIR/.secrets
ADDR=http://127.0.0.1:8200
KEY=emitkit-secrets
ROOT=${VAULT_DEV_ROOT_TOKEN:-dev-root}

fail() { echo "verify FAILED: $*" >&2; exit 1; }
compose() { docker compose -f "$DIR/docker-compose.yml" "$@"; }

# vault_as TOKEN ARGS... runs the vault CLI with TOKEN ("" for none).
vault_as() {
  token=$1; shift
  if command -v vault >/dev/null 2>&1; then
    VAULT_ADDR=$ADDR VAULT_TOKEN=$token vault "$@"
  else
    compose exec -T -e VAULT_ADDR=$ADDR -e VAULT_TOKEN="$token" vault vault "$@"
  fi
}

# expect_denied DESC TOKEN ARGS... the command must fail with "permission denied".
expect_denied() {
  desc=$1; token=$2; shift 2
  if out=$(vault_as "$token" "$@" 2>&1); then fail "$desc: expected permission denied, but it succeeded"; fi
  case "$out" in *"permission denied"*) ;; *) fail "$desc: failed, but not with permission denied: $out" ;; esac
}

for f in api-role-id api-secret-id worker-role-id worker-secret-id; do
  [ -s "$SECRETS/$f" ] || fail "missing $SECRETS/$f (start the stack: docker compose -f deploy/compose/docker-compose.yml up -d --wait)"
done

# login ROLE prints the client token for an AppRole.
login() {
  vault_as "" write -field=token auth/approle/login \
    role_id="$(cat "$SECRETS/$1-role-id")" secret_id="$(cat "$SECRETS/$1-secret-id")"
}
API=$(login api) || fail "AppRole login failed for emitkit-api"
WORKER=$(login worker) || fail "AppRole login failed for emitkit-worker"

b64() { printf '%s' "$1" | base64 | tr -d '\n'; }
CTX=$(b64 "t:c:1")
PT=$(b64 hello)

# api: encrypt and decrypt round-trip, plus rewrap (rotation job).
CT=$(vault_as "$API" write -field=ciphertext transit/encrypt/$KEY plaintext="$PT" context="$CTX") \
  || fail "api could not encrypt"
case "$CT" in vault:v1:*) ;; *) fail "api ciphertext does not start with vault:v1: ($CT)" ;; esac
GOT=$(vault_as "$API" write -field=plaintext transit/decrypt/$KEY ciphertext="$CT" context="$CTX") \
  || fail "api could not decrypt"
[ "$GOT" = "$PT" ] || fail "api decrypt round-trip mismatch"
vault_as "$API" write -field=ciphertext transit/rewrap/$KEY ciphertext="$CT" context="$CTX" >/dev/null \
  || fail "api could not rewrap"

# api: key export is denied.
expect_denied "api key export" "$API" read transit/export/encryption-key/$KEY

# worker: encrypt and decrypt round-trip.
WCT=$(vault_as "$WORKER" write -field=ciphertext transit/encrypt/$KEY plaintext="$PT" context="$CTX") \
  || fail "worker could not encrypt"
GOT=$(vault_as "$WORKER" write -field=plaintext transit/decrypt/$KEY ciphertext="$WCT" context="$CTX") \
  || fail "worker could not decrypt"
[ "$GOT" = "$PT" ] || fail "worker decrypt round-trip mismatch"

# worker: rewrap is denied (a valid ciphertext, so only the policy can block it).
expect_denied "worker rewrap" "$WORKER" write transit/rewrap/$KEY ciphertext="$CT" context="$CTX"

# key settings, read as root: the Transit key must match ov §3.1.
[ "$(vault_as "$ROOT" read -field=type transit/keys/$KEY)" = aes256-gcm96 ] || fail "transit key $KEY is not aes256-gcm96"
[ "$(vault_as "$ROOT" read -field=derived transit/keys/$KEY)" = true ] || fail "transit key $KEY is not derived"
[ "$(vault_as "$ROOT" read -field=exportable transit/keys/$KEY)" = false ] || fail "transit key $KEY is exportable"

echo "vault ok"
