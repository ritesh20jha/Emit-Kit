#!/usr/bin/env bash
# Stack for the Playwright parity suite (frontend/e2e/parity). The production stack with the smoke overlay (Vault dev
# mode, throwaway AppRole secrets, Caddy on internal TLS on https://localhost, a placeholder sandbox socket), plus the
# e2e overlay, which replaces GitHub with WireMock. Never use it in production.
#   bash deploy/compose/e2e.sh up     generate secrets, start the stack and wait until it is healthy
#   bash deploy/compose/e2e.sh down   stop it and remove its volumes and the scratch secrets
# Two modes, chosen by E2E_SANDBOX_SOCKET:
#   unset (default)   the worker is not started (--scale worker=0): there is no sandbox daemon, so the pipeline is off.
#                     The parity suite does not use the worker. Starting it with the placeholder socket only crash-loops.
#   E2E_SANDBOX_SOCKET=/path/to/docker.sock   the worker starts with that real sandbox daemon socket, pipeline on.
set -euo pipefail
cd "$(dirname "$0")"

PROJECT=emitkit-e2e
SECRETS=e2e-secrets
ENV_FILE=$SECRETS/e2e.env

compose() {
  docker compose -p "$PROJECT" -f docker-compose.prod.yml -f docker-compose.smoke.yml -f docker-compose.e2e.yml \
    --env-file "$ENV_FILE" "$@"
}

fail() { echo "e2e FAILED: $*" >&2; exit 1; }

clean_secrets() {
  # vault-init runs as root, so the files it writes are root-owned. Remove them through a container.
  if [ -d "$SECRETS" ]; then
    docker run --rm --network none -v "$PWD/$SECRETS:/s" alpine sh -c 'rm -rf /s/* /s/.[!.]*' >/dev/null 2>&1 || true
    rmdir "$SECRETS" 2>/dev/null || true
  fi
}

up() {
  if [ -n "${E2E_SANDBOX_SOCKET:-}" ]; then
    [ -S "$E2E_SANDBOX_SOCKET" ] || fail "E2E_SANDBOX_SOCKET=$E2E_SANDBOX_SOCKET is not a socket"
  fi
  trap 'status=$?; if [ "$status" -ne 0 ]; then compose logs --tail 200 >&2 || true; fi; exit "$status"' EXIT

  mkdir -p "$SECRETS"
  # The smoke env file, with the public host name set to localhost so the login redirects land on the edge.
  bash gen-env.sh | sed 's/^EMITKIT_SITE_ADDRESS=.*/EMITKIT_SITE_ADDRESS=localhost/' > "$ENV_FILE"
  # Placeholder for the SBX-01 daemon socket; the real one is not in CI. Docker would create a directory for a missing file.
  [ -n "${E2E_SANDBOX_SOCKET:-}" ] || touch "$SECRETS/sandbox.sock"
  # Without a sandbox daemon the worker cannot pass its startup check, so it is left out rather than crash-looping.
  WORKER_SCALE=()
  if [ -z "${E2E_SANDBOX_SOCKET:-}" ]; then
    WORKER_SCALE=(--scale worker=0)
    echo "e2e pipeline off: no E2E_SANDBOX_SOCKET, so the worker is not started"
  fi

  compose up -d --wait vault vault-init
  API_VAULT_ROLE_ID=$(cat "$SECRETS/api-role-id")
  WORKER_VAULT_ROLE_ID=$(cat "$SECRETS/worker-role-id")
  BACKUP_VAULT_ROLE_ID=$(cat "$SECRETS/backup-role-id")
  export API_VAULT_ROLE_ID WORKER_VAULT_ROLE_ID BACKUP_VAULT_ROLE_ID
  # One service at a time: the api and worker Gradle builds share the BuildKit cache mount for /root/.gradle, and
  # running them together makes the second one wait on the other's journal lock until it fails.
  COMPOSE_PARALLEL_LIMIT=1 compose up -d --wait --build ${WORKER_SCALE[@]+"${WORKER_SCALE[@]}"}
  echo "e2e stack up: https://localhost (GitHub mock on http://127.0.0.1:8089)"
}

down() {
  # Compose needs every required variable to read the project, so a missing env file is replaced by a throwaway one.
  mkdir -p "$SECRETS"
  [ -f "$ENV_FILE" ] || bash gen-env.sh > "$ENV_FILE"
  compose down -v --remove-orphans
  clean_secrets
  echo "e2e stack down"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) echo "usage: bash deploy/compose/e2e.sh up|down" >&2; exit 2 ;;
esac
