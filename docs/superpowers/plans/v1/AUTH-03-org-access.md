# AUTH-03 · OrgAccessService + orgs & members endpoints

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One authorization service every org- and project-scoped endpoint uses, plus the orgs and members endpoints.

**Lane:** API · **Depends on:** [AUTH-01](AUTH-01-github-login.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/auth-03`

**Spec:** v1 §3 (permission table, 404 vs 403), v1 §8 orgs/members rows

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/access/Permission.java`, `RolePermissions.java`, `OrgAccessService.java`, `ProjectAccess.java`, `Membership.java`
- Modify: `backend/api-app/src/main/java/dev/emitkit/api/orgs/OrgController.java`, `MembersController.java` (implement CON-01 bodies)
- Test: `backend/api-app/src/test/java/dev/emitkit/api/access/RolePermissionsTest.java`, `OrgAccessServiceIT.java`, `backend/api-app/src/test/java/dev/emitkit/api/orgs/MembersControllerIT.java`

## Interfaces

- **Produces:**
```java
public enum Permission { ORG_VIEW, PROJECT_VIEW, RUN_VIEW, SPEC_VIEW, PROJECT_CREATE, PROJECT_DELETE, CONFIG_EDIT, SPEC_EDIT,
  RUN_TRIGGER, RUN_CANCEL, RUN_RERUN, RUN_APPROVE, MEMBER_ROLE_EDIT }
public final class RolePermissions { public static Set<Permission> of(Role role); }   // exactly v1 §3 table
public record Membership(String orgId, String userId, Role role) {}
public class OrgAccessService { public Membership require(String orgId, Permission p); }      // current user from SecurityContext
public class ProjectAccess   { public Project require(String projectId, Permission p); }      // loads project, then OrgAccessService on its org
```
- Not a member (or org/project missing) → `AppException(NOT_FOUND)`; member with low role → `AppException(FORBIDDEN)`.
- `PATCH members/{userId}`: sets `role_source=MANUAL`; demoting the last ADMIN → `AppException(VALIDATION_FAILED, "An organization needs at least one admin")`.

## Tasks

### Task 1: Permission table

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @CsvSource({
  "ADMIN, ORG_VIEW, true", "ADMIN, MEMBER_ROLE_EDIT, true", "ADMIN, RUN_APPROVE, true",
  "EDITOR, PROJECT_CREATE, true", "EDITOR, SPEC_EDIT, true", "EDITOR, RUN_TRIGGER, true",
  "EDITOR, PROJECT_DELETE, false", "EDITOR, CONFIG_EDIT, false", "EDITOR, RUN_APPROVE, false", "EDITOR, MEMBER_ROLE_EDIT, false",
  "VIEWER, RUN_VIEW, true", "VIEWER, PROJECT_CREATE, false", "VIEWER, SPEC_EDIT, false", "VIEWER, RUN_TRIGGER, false"})
void matchesSpecTable(Role role, Permission p, boolean allowed) { assertThat(RolePermissions.of(role).contains(p)).isEqualTo(allowed); }
@Test void adminHasEverything() { assertThat(RolePermissions.of(ADMIN)).containsExactlyInAnyOrder(Permission.values()); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*RolePermissionsTest'`

- [ ] **Step 3: Implement** — `RolePermissions` as an `EnumMap`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*RolePermissionsTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(access): role permission table"`

### Task 2: Access service + endpoints

- [ ] **Step 1: Write the failing test**

```java
@Test void nonMember_gets404NotFound() { as(stranger); mvc.perform(get("/api/v1/orgs/{id}", acme)).andExpect(status().isNotFound()); }
@Test void viewerPatchingRole_gets403() { }
@Test void adminSetsRole_markedManual() { /* PATCH → role EDITOR, roleSource MANUAL */ }
@Test void demotingLastAdmin_rejected() { /* 400 VALIDATION_FAILED */ }
@Test void orgsList_onlyMyOrgs_withMemberCount() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*OrgAccessServiceIT' --tests '*MembersControllerIT'`

- [ ] **Step 3: Implement** — services + controller bodies.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*OrgAccessServiceIT' --tests '*MembersControllerIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(orgs): org access service and members endpoints"`

---

## Done when

- [ ] All tasks above are committed on `ticket/auth-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
