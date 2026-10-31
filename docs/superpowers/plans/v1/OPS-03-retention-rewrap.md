# OPS-03 · Retention + key re-wrap jobs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nightly cleanup of old logs and artifacts, and a re-wrap job for Vault key rotation, each running once across replicas.

**Lane:** API · **Depends on:** [DB-04](DB-04-run-results-schema.md), [OBJ-01](OBJ-01-artifact-storage.md), [CORE-02](CORE-02-secret-cipher.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/ops-03`

**Spec:** v1 §4 retention; ov §3.1 key rotation

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/resources/db/migration/V5__shedlock.sql`
- Create: `backend/api-app/src/main/java/dev/emitkit/api/jobs/RetentionJob.java`, `RewrapJob.java`, `EncryptedColumns.java`, `SchedulingConfig.java`
- Modify: `backend/common/src/main/java/dev/emitkit/common/secret/SecretCipher.java` — add `List<String> rewrapBatch(List<CipherItem> items)` (Vault `transit/rewrap`; `LocalAesCipher` returns input unchanged)
- Test: `backend/api-app/src/test/java/dev/emitkit/api/jobs/OPS03JobsIT.java`

## Interfaces

- **Produces:**
- ShedLock with the JDBC provider; `@SchedulerLock(name=..., lockAtMostFor="PT30M")`.
- `RetentionJob` cron `0 0 3 * * *` UTC: delete `generation_run_logs` with `ts < now-90d` (batches of 10 000); for `run_artifacts` with `created_at < now-30d`: `ArtifactStorage.delete(key)` then delete the row. Run rows are kept.
- `EncryptedColumns.ALL` = `oauth_accounts.access_token_enc`, `projects.webhook_secret_enc`, `project_configs.ai_api_key_enc` (table, column, id column).
- `RewrapJob` — manual trigger only in v1 (`POST /internal/rewrap` on the management port) + optional cron property: for each column, page through non-null rows in batches of 200 whose ciphertext version `< latest` (read from `transit/keys/emitkit-secrets` `latest_version`), call `rewrapBatch`, update; returns `{column: remainingBelowLatest}`.

## Tasks

### Task 1: Retention + key re-wrap jobs

- [ ] **Step 1: Write the failing test**

```java
@Test void retention_deletesOnlyOldLogsAndArtifacts() { }
@Test void retention_keepsRunRows() { }
@Test void rewrap_updatesVersion_plaintextUnchanged() { /* Vault container: encrypt v1, rotate key, run job → ciphertext starts vault:v2:, decrypt equals original */ }
@Test void rewrap_reportsZeroRemaining() { }
@Test void lockPreventsConcurrentRuns() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*OPS03*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*OPS03*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): retention + key re-wrap jobs"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ops-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
