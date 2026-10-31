# OPS-02 · Backups + restore drill

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nightly off-host backups of Postgres, Vault and RustFS, and a scripted restore drill that proves secrets still decrypt.

**Lane:** OPS · **Depends on:** [OPS-01](OPS-01-prod-compose.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/ops-02`

**Spec:** v1 §11 backups

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `deploy/compose/backup/` — `backup.sh` (pg_dump + WAL archive config, `vault operator raft snapshot save` every 6 h, `rclone sync` of the RustFS bucket), `crontab`, service in `docker-compose.prod.yml`
- Create: `scripts/restore-drill.sh`, `docs/runbooks/restore.md`

## Interfaces

- **Produces:**
- Off-host target: any S3-compatible bucket (`BACKUP_S3_*` env); 7-day retention via lifecycle or `rclone delete --min-age 7d`.
- Restore drill: fresh compose project → restore Postgres dump, Vault snapshot (`raft snapshot restore -force`), RustFS mirror → unseal → run checks.

## Tasks

### Task 1: Backups + restore drill

- [ ] **Step 1: Write the failing test**

```bash
# scripts/restore-drill.sh (run in CI against compose)
seed_data; ./backup.sh now
docker compose -p drill up -d --wait && restore_all drill
[ "$(psql_drill 'select count(*) from projects')" = "$SEEDED_PROJECTS" ] || fail "row count"
curl -fs drill-api/internal/selftest/decrypt?projectId=$P | grep -q ok   || fail "transit decrypt after restore"
echo "restore ok"
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `bash scripts/restore-drill.sh`

- [ ] **Step 3: Implement** — as listed; `/internal/selftest/decrypt` is a management-port endpoint added in this PR that decrypts one webhook secret and returns only `ok`/`fail`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `bash scripts/restore-drill.sh`

- [ ] **Step 5: Commit** — `git commit -m "ops: backups + restore drill"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ops-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
