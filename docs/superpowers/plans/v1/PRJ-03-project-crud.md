# PRJ-03 · Project get/list/delete + webhook re-register

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read, list and delete projects, and re-register a missing or failing webhook with a new secret.

**Lane:** API · **Depends on:** [PRJ-01](PRJ-01-create-existing.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/prj-03`

**Spec:** v1 §8 (GET/DELETE project, POST webhook)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/api-app/src/main/java/dev/emitkit/api/projects/ProjectController.java`, `ProjectService.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/PRJ03ProjectCrudIT.java`

## Interfaces

- **Produces:**
- `GET /orgs/{orgId}/projects` → `Page<ProjectSummary>` (keyset on `created_at,id`; `q` filters `repo_full_name ILIKE`), last-run fields via one join query (no N+1).
- `DELETE /projects/{id}` (`PROJECT_DELETE`): best-effort `deleteWebhook` (failure → WARN log with projectId, continue), then **soft-delete** the project: runs are kept (v1 §4 retention), so add `deleted_at timestamptz NULL` to `projects` in `V6__project_soft_delete.sql` in this PR, filter it with `@SQLRestriction("deleted_at IS NULL")`, and drop the `UNIQUE(org_id, repo_full_name)` in favour of a partial unique index `WHERE deleted_at IS NULL` so the repo can be added again.
- `POST /projects/{id}/webhook` (`CONFIG_EDIT`): delete old hook (ignore 404), register new one with a new secret, save both.

## Tasks

### Task 1: Project get/list/delete + webhook re-register

- [ ] **Step 1: Write the failing test**

```java
@Test void delete_removesWebhook_andHidesProject() { }
@Test void delete_webhookFails_stillDeletes_logsWarning() { }
@Test void deletedProjectGives404() { }
@Test void reRegister_rotatesSecret() { var before = secretOf(p); post(webhook); assertThat(secretOf(p)).isNotEqualTo(before); }
@Test void list_paginatesAndFilters() { }
@Test void list_includesLastRunStatus() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ03*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ03*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): project get/list/delete + webhook re-register"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
