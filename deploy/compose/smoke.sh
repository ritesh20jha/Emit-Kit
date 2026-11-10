#!/usr/bin/env bash
# Smoke test for the production stack. Brings up docker-compose.prod.yml with the test overlay
# (Vault in dev mode with throwaway AppRole secrets, Caddy on internal TLS, a placeholder sandbox
# socket) and checks the edge, the published ports and the worker mounts. Needs Docker with Compose.
# Runs from any directory: bash deploy/compose/smoke.sh
set -euo pipefail
cd "$(dirname "$0")"

PROJECT=emitkit-smoke
SECRETS=smoke-secrets
PROD=(docker compose -p "$PROJECT" -f docker-compose.prod.yml --env-file .env.prod.example)
STACK=(docker compose -p "$PROJECT" -f docker-compose.prod.yml -f docker-compose.smoke.yml --env-file .env.prod.example)

fail() { echo "smoke FAILED: $*" >&2; exit 1; }

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    "${STACK[@]}" logs --tail 200 >&2 || true
  fi
  "${STACK[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  # vault-init runs as root, so the files it writes are root-owned. Remove them through a container.
  if [ -d "$SECRETS" ]; then
    docker run --rm --network none -v "$PWD/$SECRETS:/s" alpine sh -c 'rm -rf /s/* /s/.[!.]*' >/dev/null 2>&1 || true
    rmdir "$SECRETS" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

"${PROD[@]}" config -q

mkdir -p "$SECRETS"
touch "$SECRETS/sandbox.sock" # placeholder for the SBX-01 daemon socket; the real one is not in CI

"${STACK[@]}" up -d --wait vault vault-init
API_VAULT_ROLE_ID=$(cat "$SECRETS/api-role-id")
WORKER_VAULT_ROLE_ID=$(cat "$SECRETS/worker-role-id")
export API_VAULT_ROLE_ID WORKER_VAULT_ROLE_ID

"${STACK[@]}" up -d --wait --build

# Only Caddy's 80 and 443 may be published on the host.
published=$(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Ports}}' |
  grep -oE '[0-9]+->' | tr -d '>-' | sort -nu | paste -sd' ' - || true)
[ "$published" = "80 443" ] || fail "published host ports are '$published', want '80 443'"

code=$(curl -sk --retry 20 --retry-connrefused --retry-delay 2 -o /dev/null -w '%{http_code}' https://localhost/api/v1/me)
[ "$code" = 401 ] || fail "GET /api/v1/me returned $code, want 401"

body=$(curl -sk --retry 5 --retry-connrefused --retry-delay 2 https://localhost/)
case "$body" in *'<div id="root">'*) ;; *) fail "GET / did not return the SPA shell" ;; esac

# Worker has 2 replicas; check the first. The Docker socket must not be mounted; the sandbox socket must be.
"${STACK[@]}" exec -T --index 1 worker sh -c 'test ! -S /var/run/docker.sock' ||
  fail "worker has /var/run/docker.sock mounted"
"${STACK[@]}" exec -T --index 1 worker sh -c 'test -e /run/emitkit-sandbox/docker.sock' ||
  fail "worker has no sandbox socket mounted"

echo "prod stack ok"
