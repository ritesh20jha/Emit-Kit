# PRJ-01 · Create project (existing repo) + repo picker + spec-check

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a project from an existing repo: pick the repo, check the spec, save project + default config, register the webhook.

**Lane:** API · **Depends on:** [AUTH-03](AUTH-03-org-access.md), [VCS-04](VCS-04-webhooks-hmac.md), [DB-02](DB-02-projects-schema.md), [CON-02](CON-02-contract-projects.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/prj-01`

**Spec:** v1 §8 (vcs-repos, spec-check, POST projects), §10.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/projects/ProjectService.java`, `ProjectController.java` (implement list/create), `RepoPickerController.java`, `WebhookRegistrar.java`, `DefaultConfig.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/PRJ01CreateProjectIT.java` (Postgres + `InMemoryVcsClient` bean override)

## Interfaces

- **Produces:**
- `ProjectService.createExisting(String orgId, CreateProjectRequest r, String userId): Project`:
  1. `require(orgId, PROJECT_CREATE)`; `existsByOrgIdAndRepoFullName` → `AppException(CONFLICT, "Project already exists for this repo")`.
  2. `getFile(repo, specPath, defaultBranch)` must exist (missing → `SPEC_NOT_FOUND`).
  3. In a transaction: insert project (id first), config version 1 from `DefaultConfig` (outputs `[SDK]`, languages `[typescript]`, npmName = repo name, others per DB-02 defaults).
  4. After commit: generate 32 random bytes hex secret → `registerWebhook(repo, "{emitkit.public-url}/webhooks/github", secret, {"push","pull_request"})` → save `webhook_id` and `webhook_secret_enc` (context `projects:webhook_secret_enc:{projectId}`).
  5. If step 4 fails: delete the project + config (compensation) and rethrow as the mapped VCS error.
- `GET vcs-repos?q` filters `listRepos()` by substring, max 50. `spec-check` runs `SpecParser` on the fetched file; never throws for a missing file (`found=false`).
- `SEPARATE` mode requires `outputRepo` (else `VALIDATION_FAILED`) and checks it exists.

## Tasks

### Task 1: Create project (existing repo) + repo picker + spec-check

- [ ] **Step 1: Write the failing test**

```java
@Test void createExisting_savesProjectConfigAndWebhook() { var p = create(EXISTING, "acme/api"); assertThat(p.getWebhookId()).isNotNull(); assertThat(configs.findLatest(p.getId())).get().extracting(ProjectConfig::getVersion).isEqualTo(1); assertThat(vcs.webhooks("acme/api")).hasSize(1); }
@Test void webhookSecretStoredEncrypted_withProjectContext() { }
@Test void duplicateRepo_returns409() { }
@Test void specMissing_returns404SpecNotFound() { }
@Test void webhookRegistrationFails_projectNotKept() { vcs.failNext(new VcsAuthException("no hook scope")); assertThatThrownBy(...); assertThat(projects.count()).isZero(); }
@Test void separateModeWithoutOutputRepo_400() { }
@Test void specCheck_reportsTitleAndVersion() { }
@Test void editorCanCreate_viewerCannot() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ01*'`

- [ ] **Step 3: Implement** — as listed; inject `Clock` and `SecureRandom` for tests.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ01*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): create project (existing repo) + repo picker + spec-check"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
