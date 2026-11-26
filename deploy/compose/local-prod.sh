#!/usr/bin/env bash
# The production stack on your machine, with real GitHub. The production compose file plus the smoke overlay (Vault in
# dev mode, throwaway AppRole secrets, Caddy on internal TLS at https://localhost) plus the local overlay (real sandbox
# daemon, pipeline on). Never use it in production.
#   bash deploy/compose/local-prod.sh up     generate secrets, build and start the stack, wait until it is healthy
#   bash deploy/compose/local-prod.sh down   stop it and remove its volumes and the scratch secrets
#   bash deploy/compose/local-prod.sh logs [service]
#
# GitHub OAuth client: set SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID and ..._CLIENT_SECRET in the
# environment or in the repo-root .env. The OAuth app's callback URL must be https://localhost/login/oauth2/code/github.
# Optional environment:
#   LOCAL_PUBLIC_URL        tunnel URL GitHub can reach, for webhooks (default https://localhost, which GitHub cannot reach)
#   LOCAL_SANDBOX_SOCKET    sandbox daemon socket (default /run/emitkit-sandbox/docker.sock)
#   LOCAL_TOOLCHAIN_IMAGE   toolchain image in that daemon (default emitkit/toolchain:test)
set -euo pipefail
cd "$(dirname "$0")"

PROJECT=emitkit-local
SECRETS=local-secrets
ENV_FILE=$SECRETS/local.env
ROOT_ENV=../../.env
SOCKET=${LOCAL_SANDBOX_SOCKET:-/run/emitkit-sandbox/docker.sock}
IMAGE=${LOCAL_TOOLCHAIN_IMAGE:-emitkit/toolchain:test}
ID_KEY=SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID
SECRET_KEY=SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET

compose() {
  docker compose -p "$PROJECT" -f docker-compose.prod.yml -f docker-compose.smoke.yml -f docker-compose.local.yml \
    --env-file "$ENV_FILE" "$@"
}

fail() { echo "local-prod FAILED: $*" >&2; exit 1; }

clean_secrets() {
  # vault-init runs as root, so the files it writes are root-owned. Remove them through a container.
  if [ -d "$SECRETS" ]; then
    docker run --rm --network none -v "$PWD/$SECRETS:/s" alpine sh -c 'rm -rf /s/* /s/.[!.]*' >/dev/null 2>&1 || true
    rmdir "$SECRETS" 2>/dev/null || true
  fi
}

# Value of KEY from the environment, else from the root .env. The file is parsed, never sourced.
lookup() {
  local key=$1 value=${!1:-}
  if [ -z "$value" ] && [ -f "$ROOT_ENV" ]; then
    value=$(sed -n "s/^$key=//p" "$ROOT_ENV" | tail -n 1 | sed 's/[[:space:]]*#.*$//; s/^["'\'']//; s/["'\'']$//')
  fi
  printf '%s' "$value"
}

up() {
  [ -S "$SOCKET" ] || fail "$SOCKET is not a socket; start the sandbox daemon (deploy/sandbox/setup-rootless-gvisor.sh)"
  local client_id client_secret
  client_id=$(lookup "$ID_KEY")
  client_secret=$(lookup "$SECRET_KEY")
  [ -n "$client_id" ] && [ -n "$client_secret" ] || fail "set $ID_KEY and $SECRET_KEY (environment or $ROOT_ENV)"
  # A warning only: the check needs access to the socket from this shell, which a fresh group may not give yet.
  docker -H "unix://$SOCKET" image inspect "$IMAGE" >/dev/null 2>&1 ||
    echo "warning: cannot see image $IMAGE in the sandbox daemon; if runs fail, load it with: docker save $IMAGE | docker -H unix://$SOCKET load" >&2
  trap 'status=$?; if [ "$status" -ne 0 ]; then compose logs --tail 200 >&2 || true; fi; exit "$status"' EXIT

  mkdir -p "$SECRETS"
  # The generated env file, with the public host name set to localhost and the real OAuth client and sandbox group.
  # Values go through the environment, not the command line, so they are not printed and need no escaping.
  sandbox_gid=$(getent group emitkit-sandbox | cut -d: -f3 || true)
  [ -n "$sandbox_gid" ] || fail "no emitkit-sandbox group on this host"
  bash gen-env.sh | CID="$client_id" CSECRET="$client_secret" GID_="$sandbox_gid" awk '
    /^EMITKIT_SITE_ADDRESS=/ { print "EMITKIT_SITE_ADDRESS=localhost"; next }
    /^GITHUB_CLIENT_ID=/     { print "GITHUB_CLIENT_ID=" ENVIRON["CID"]; next }
    /^GITHUB_CLIENT_SECRET=/ { print "GITHUB_CLIENT_SECRET=" ENVIRON["CSECRET"]; next }
    /^SANDBOX_GID=/          { print "SANDBOX_GID=" ENVIRON["GID_"]; next }
    { print }' > "$ENV_FILE"
  chmod 600 "$ENV_FILE"

  export LOCAL_SANDBOX_SOCKET=$SOCKET LOCAL_TOOLCHAIN_IMAGE=$IMAGE
  compose up -d --wait vault vault-init
  API_VAULT_ROLE_ID=$(cat "$SECRETS/api-role-id")
  WORKER_VAULT_ROLE_ID=$(cat "$SECRETS/worker-role-id")
  BACKUP_VAULT_ROLE_ID=$(cat "$SECRETS/backup-role-id")
  export API_VAULT_ROLE_ID WORKER_VAULT_ROLE_ID BACKUP_VAULT_ROLE_ID
  # One service at a time: the api and worker Gradle builds share the BuildKit cache mount for /root/.gradle, and
  # running them together makes the second one wait on the other's journal lock until it fails.
  COMPOSE_PARALLEL_LIMIT=1 compose up -d --wait --build
  echo "local prod stack up: https://localhost (accept the self-signed certificate)"
  echo "GitHub OAuth callback must be: https://localhost/login/oauth2/code/github"
  echo "webhook URL: ${LOCAL_PUBLIC_URL:-https://localhost}/webhooks/github"
}

down() {
  # Compose needs every required variable to read the project, so a missing env file is replaced by a throwaway one.
  mkdir -p "$SECRETS"
  [ -f "$ENV_FILE" ] || bash gen-env.sh > "$ENV_FILE"
  compose down -v --remove-orphans
  clean_secrets
  echo "local prod stack down"
}

logs() {
  [ -f "$ENV_FILE" ] || fail "the stack is not up"
  shift
  compose logs -f --tail 100 "$@"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  logs) logs "$@" ;;
  *) echo "usage: bash deploy/compose/local-prod.sh up|down|logs [service]" >&2; exit 2 ;;
esac
