# PRJ-04 · Config get/put

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read and version the project config; the AI key is write-only and encrypted.

**Lane:** API · **Depends on:** [PRJ-01](PRJ-01-create-existing.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/prj-04`

**Spec:** v1 §4 project_configs, §8 config rows, §10.5

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/projects/ConfigService.java`, `ConfigController.java`, `ConfigValidator.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/PRJ04ConfigIT.java`

## Interfaces

- **Produces:**
- `PUT` inserts version `n+1` (append-only). `aiApiKey`: `null` → copy previous `ai_api_key_enc` (re-encrypted with the **new row's** id context, since context includes row id: decrypt old → encrypt new); `""` → clear; value → encrypt with `project_configs:ai_api_key_enc:{newConfigId}`.
- Validation (`VALIDATION_FAILED` with field errors): `SDK` in outputs needs ≥ 1 language from `typescript|python|java`; typescript needs `npmScope` pattern `^[a-z0-9-~][a-z0-9-._~]*$` or empty; python needs `pypiName`; java needs `mavenGroupId` + `mavenArtifactId`; `outputDir` must be relative, no `..`, end with `/`.
- `GET` returns latest; `aiKeySet = ai_api_key_enc != null`; never returns the key.

## Tasks

### Task 1: Config get/put

- [ ] **Step 1: Write the failing test**

```java
@Test void put_createsNextVersion() { }
@Test void get_neverReturnsKey_reportsAiKeySet() { put(cfg.withAiApiKey("sk-1")); assertThat(getJson()).doesNotContain("sk-1").contains("\"aiKeySet\":true"); }
@Test void nullKey_keepsPreviousKey_reEncryptedForNewRow() { }
@Test void emptyKey_clearsKey() { }
@Test void sdkWithoutLanguages_400() { }
@Test void outputDirWithDotDot_400() { }
@Test void editorPut_403() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ04*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ04*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): config get/put"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
