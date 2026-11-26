# Backup job: takes Raft snapshots and nothing else. Restores are done by an operator with a root or admin token.
path "sys/storage/raft/snapshot" { capabilities = ["read"] }
