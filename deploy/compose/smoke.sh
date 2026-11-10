#!/usr/bin/env bash
# Smoke test for the production stack. Three steps:
#  1. The raw example must be refused (its empty secrets), and a generated env file must pass compose config.
#  2. The production Vault service boots from vault.hcl with no overlay (throwaway project and volume).
#  3. The full stack runs with a test overlay (Vault in dev mode with throwaway AppRole secrets, Caddy on
#     internal TLS, a placeholder sandbox socket). Checks the edge, published ports, worker mounts and failover.
# Needs Docker with Compose. Runs from any directory: bash deploy/compose/smoke.sh
set -euo pipefail
cd "$(dirname "$0")"

PROJECT=emitkit-smoke
VAULT_PROJECT=emitkit-smoke-vault
SECRETS=smoke-secrets
ENV_FILE=$SECRETS/smoke.env
PROD=(docker compose -p "$PROJECT" -f docker-compose.prod.yml --env-file "$ENV_FILE")
STACK=(docker compose -p "$PROJECT" -f docker-compose.prod.yml -f docker-compose.smoke.yml --env-file "$ENV_FILE")
VAULT_ONLY=(docker compose -p "$VAULT_PROJECT" -f docker-compose.prod.yml --env-file "$ENV_FILE")
PAUSED=""

fail() { echo "smoke FAILED: $*" >&2; exit 1; }

cleanup() {
  status=$?
  if [ -n "$PAUSED" ]; then docker unpause "$PAUSED" >/dev/null 2>&1 || true; fi
  if [ "$status" -ne 0 ]; then
    "${STACK[@]}" logs --tail 200 >&2 || true
  fi
  "${STACK[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  "${VAULT_ONLY[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  # vault-init runs as root, so the files it writes are root-owned. Remove them through a container.
  if [ -d "$SECRETS" ]; then
    docker run --rm --network none -v "$PWD/$SECRETS:/s" alpine sh -c 'rm -rf /s/* /s/.[!.]*' >/dev/null 2>&1 || true
    rmdir "$SECRETS" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

# 1. The example must not start as it is: its empty secrets have to make compose fail.
if docker compose -f docker-compose.prod.yml --env-file .env.prod.example config -q >/dev/null 2>&1; then
  fail "compose accepted .env.prod.example with empty secrets"
fi
mkdir -p "$SECRETS"
bash gen-env.sh > "$ENV_FILE"
"${PROD[@]}" config -q

# 2. Production Vault boots from vault.hcl. Sealed and uninitialized is exit code 2 from `vault status`.
"${VAULT_ONLY[@]}" up -d --wait vault
vault_cid=$("${VAULT_ONLY[@]}" ps -q vault)
[ "$(docker inspect -f '{{.RestartCount}}' "$vault_cid")" = 0 ] || fail "prod vault restarted during boot"
rc=0
vault_status=$("${VAULT_ONLY[@]}" exec -T vault vault status 2>&1) || rc=$?
[ "$rc" = 2 ] || fail "prod vault status exit $rc, want 2 (sealed)"
grep -qE '^Initialized +false' <<< "$vault_status" || fail "prod vault is already initialized"
grep -qE '^Sealed +true' <<< "$vault_status" || fail "prod vault is not sealed"
"${VAULT_ONLY[@]}" down -v --remove-orphans >/dev/null
echo "prod vault boots: sealed and uninitialized"

# 3. Full stack with the test overlay.
touch "$SECRETS/sandbox.sock" # placeholder for the SBX-01 daemon socket; the real one is not in CI
"${STACK[@]}" up -d --wait vault vault-init
API_VAULT_ROLE_ID=$(cat "$SECRETS/api-role-id")
WORKER_VAULT_ROLE_ID=$(cat "$SECRETS/worker-role-id")
export API_VAULT_ROLE_ID WORKER_VAULT_ROLE_ID
"${STACK[@]}" up -d --wait --build

# The JVM services must run with their container memory limits (the defaults in .env.prod.example, 1g and 1536m).
api_mem=$(docker inspect -f '{{.HostConfig.Memory}}' "$("${STACK[@]}" ps -q api | sed -n 1p)")
worker_mem=$(docker inspect -f '{{.HostConfig.Memory}}' "$("${STACK[@]}" ps -q worker | sed -n 1p)")
[ "$api_mem" = 1073741824 ] || fail "api memory limit is $api_mem bytes, want 1g"
[ "$worker_mem" = 1610612736 ] || fail "worker memory limit is $worker_mem bytes, want 1536m"

# Only Caddy's 80 and 443 may be published on the host.
published=$(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Ports}}' |
  grep -oE '[0-9]+->' | tr -d '>-' | sort -nu | paste -sd' ' - || true)
[ "$published" = "80 443" ] || fail "published host ports are '$published', want '80 443'"

code=$(curl -sk --retry 20 --retry-connrefused --retry-delay 2 -o /dev/null -w '%{http_code}' https://localhost/api/v1/me)
[ "$code" = 401 ] || fail "GET /api/v1/me returned $code, want 401"

body=$(curl -sk --retry 5 --retry-connrefused --retry-delay 2 https://localhost/)
case "$body" in *'<div id="root">'*) ;; *) fail "GET / did not return the SPA shell" ;; esac

csp=$(curl -sk --retry 5 --retry-connrefused --retry-delay 2 -D - -o /dev/null https://localhost/ |
  tr -d '\r' | grep -i '^content-security-policy:' || true)
case "$csp" in *"default-src 'self'"*) ;; *) fail "GET / has no default-src 'self' CSP: '$csp'" ;; esac

# One api replica that stops answering must not fail requests: Caddy has to send them to the other replica.
# A request sent to the paused replica waits for Caddy's 30 s response-header timeout before the retry,
# so the per-request limit here is 60 s.
PAUSED=$("${STACK[@]}" ps -q api | sed -n 1p)
docker pause "$PAUSED" >/dev/null
for i in $(seq 10); do
  code=$(curl -sk --max-time 60 -o /dev/null -w '%{http_code}' https://localhost/api/v1/me) || code=000
  [ "$code" = 401 ] || fail "request $i with one api replica paused returned $code, want 401"
done
docker unpause "$PAUSED" >/dev/null
PAUSED=""
echo "api failover ok"

# Worker has 2 replicas; check the first. The Docker socket must not be mounted; the sandbox socket must be.
"${STACK[@]}" exec -T --index 1 worker sh -c 'test ! -S /var/run/docker.sock' ||
  fail "worker has /var/run/docker.sock mounted"
"${STACK[@]}" exec -T --index 1 worker sh -c 'test -e /run/emitkit-sandbox/docker.sock' ||
  fail "worker has no sandbox socket mounted"

echo "prod stack ok"
