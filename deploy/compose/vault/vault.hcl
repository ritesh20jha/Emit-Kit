# Production Vault: one server, integrated (Raft) storage on a named volume. Unsealed by hand after
# each restart with 3-of-5 Shamir shares (docs/runbooks/deploy.md). Reachable only on the app network.
ui           = false
disable_mlock = true # Raft storage on a container volume; the Vault container runs without IPC_LOCK.

storage "raft" {
  path    = "/vault/file"
  node_id = "vault-1"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true # Internal network only, no certificate on the app network yet. See the runbook.
}

api_addr     = "http://vault:8200"
cluster_addr = "http://vault:8201"
