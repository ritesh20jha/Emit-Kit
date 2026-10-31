#!/bin/sh
# Bootstraps the dev Vault: Transit engine and key, AppRole auth, policies and roles.
# Safe to re-run. Writes the role IDs and secret IDs to /secrets for local app runs.
set -eu

POLICIES=/policies
SECRETS=/secrets

vault secrets list | grep -q '^transit/' || vault secrets enable transit
vault auth list | grep -q '^approle/' || vault auth enable approle

vault read transit/keys/emitkit-secrets >/dev/null 2>&1 ||
  vault write transit/keys/emitkit-secrets type=aes256-gcm96 derived=true exportable=false

vault policy write emitkit-api "$POLICIES/emitkit-api.hcl"
vault policy write emitkit-worker "$POLICIES/emitkit-worker.hcl"

for app in api worker; do
  vault write "auth/approle/role/emitkit-$app" token_policies="emitkit-$app" token_ttl=1h
  vault read -field=role_id "auth/approle/role/emitkit-$app/role-id" > "$SECRETS/$app-role-id"
  vault write -f -field=secret_id "auth/approle/role/emitkit-$app/secret-id" > "$SECRETS/$app-secret-id"
done

# Dev only: Docker creates the bind mount root:root, so the host user must be able to read and delete these.
chmod 777 "$SECRETS"
chmod 644 "$SECRETS"/api-role-id "$SECRETS"/api-secret-id "$SECRETS"/worker-role-id "$SECRETS"/worker-secret-id
