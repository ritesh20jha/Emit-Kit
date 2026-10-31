# AUTH-02 · Org sync

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror the user's GitHub orgs and roles into EmitKit on login and on demand, without overwriting manual roles.

**Lane:** API · **Depends on:** [AUTH-01](AUTH-01-github-login.md), [VCS-02](VCS-02-github-files.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/auth-02`

**Spec:** v1 §3 (mapping and MANUAL rule), v1 §8 `POST /me/sync-orgs`

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/orgs/OrgSyncService.java`, `OrgSyncListener.java` (`@EventListener UserLoggedInEvent`, async on a virtual thread)
- Modify: `backend/api-app/src/main/java/dev/emitkit/api/me/MeController.java` (`POST /me/sync-orgs` → 204)
- Test: `backend/api-app/src/test/java/dev/emitkit/api/orgs/OrgSyncServiceTest.java` (with `InMemoryVcsClient` from VCS-01 test fixtures + Testcontainers Postgres)

## Interfaces

- **Produces:**
- `OrgSyncService.sync(String userId)` — `@Transactional`; uses `vcsProvider.forProject(userId, INTERACTIVE).listOrgs()`.
- Rules: upsert `organizations` by `(github, vcs_org_id)`; personal org gets `is_personal=true`, `owner_user_id=userId`; OWNER → `ADMIN`, MEMBER → `EDITOR`, `role_source=SYNC`; existing `MANUAL` membership is left untouched; a `SYNC` membership for an org no longer returned is deleted; `MANUAL` memberships are kept.
- `POST /me/sync-orgs` is limited to once per 60 s per user (Redis `SET NX EX 60`); a second call returns 429 `RATE_LIMITED` with detail "Sync ran less than a minute ago".

## Tasks

### Task 1: Sync rules

- [ ] **Step 1: Write the failing test**

```java
@Test void ownerBecomesAdmin_memberBecomesEditor() { vcs.orgs(org("acme", OWNER), org("beta", MEMBER)); sync.sync(user); assertThat(roles(user)).containsEntry("acme", ADMIN).containsEntry("beta", EDITOR); }
@Test void personalOrgCreatedAsAdmin() { }
@Test void manualRoleIsNotOverwritten() { membership("acme", VIEWER, MANUAL); vcs.orgs(org("acme", OWNER)); sync.sync(user); assertThat(roles(user)).containsEntry("acme", VIEWER); }
@Test void removedFromGitHubOrg_loseSyncMembership_keepManual() { }
@Test void syncTwice_isIdempotent() { }
@Test void syncEndpoint_throttled() { /* second POST within 60 s → 429 RATE_LIMITED */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*OrgSyncServiceTest'`

- [ ] **Step 3: Implement** — `OrgSyncService` + listener + endpoint.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*OrgSyncServiceTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(orgs): sync github orgs and roles"`

---

## Done when

- [ ] All tasks above are committed on `ticket/auth-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
