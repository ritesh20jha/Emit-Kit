# DB-01 · Migration V1: identity + entities

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Flyway migration `V1__identity` (users, oauth_accounts, organizations, org_members) with JPA entities and Spring Data repositories.

**Lane:** CORE · **Depends on:** [T01](T01-backend-skeleton.md), [CORE-01](CORE-01-common-errors-ids.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/db-01`

**Spec:** v1 §4 (tables, columns, enums, indexes)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/resources/db/migration/V1__identity.sql`
- Create: entities + repositories under `backend/domain/src/main/java/dev/emitkit/domain/` — `BaseEntity` (id, created_at, updated_at), `User`, `OAuthAccount`, `Organization`, `OrgMember` (`@IdClass OrgMemberId`), enums `Role {ADMIN, EDITOR, VIEWER}`, `RoleSource {SYNC, MANUAL}`; repos `UserRepository.findByGithubId(long)`, `OAuthAccountRepository.findByProviderAndProviderAccountId`, `OrganizationRepository.findByVcsProviderAndVcsOrgId`, `OrgMemberRepository.findByUserId`, `findByOrgId`, `countByOrgIdAndRole`
- Test: `backend/domain/src/test/java/dev/emitkit/domain/DB01RepositoriesIT.java` (extends `PostgresIT`); create `backend/domain/src/test/java/dev/emitkit/domain/PostgresIT.java` (Testcontainers `postgres:16`, `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)`, Flyway on)

## Interfaces

- **Produces:** the tables exactly as in v1 §4 (column names, `UNIQUE` constraints, defaults). Enums are stored as `varchar` with a `CHECK` constraint and mapped with `@Enumerated(STRING)`. IDs are `varchar(26)` assigned in Java with `Ids.newUlid()` in a `@PrePersist` on `BaseEntity` (DB-01). JSONB columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.
- `organizations.vcs_provider` defaults to `'github'`. `access_token_enc` is plain `text` here; encryption happens in AUTH-01.

## Tasks

### Task 1: Migration V1__identity + entities

- [ ] **Step 1: Write the failing test**

```java
@Test void duplicateOAuthAccount_violatesUnique() {
  oauth.saveAndFlush(account("github", "42"));
  assertThatThrownBy(() -> oauth.saveAndFlush(account("github", "42")))
      .isInstanceOf(DataIntegrityViolationException.class);
}
@Test void duplicateOrg_violatesUnique() { /* same (vcs_provider, vcs_org_id) twice */ }
@Test void memberHasCompositeKey_andRoleRoundTrips() {
  members.saveAndFlush(new OrgMember(org.getId(), user.getId(), Role.EDITOR, RoleSource.MANUAL));
  assertThat(members.findByUserId(user.getId())).singleElement()
      .extracting(OrgMember::getRole, OrgMember::getRoleSource).containsExactly(Role.EDITOR, RoleSource.MANUAL);
}
@Test void invalidRoleValue_rejectedByCheckConstraint() { /* native insert with role='OWNER' fails */ }
@Test void idIsUlidAssignedBeforeInsert() { assertThat(users.save(newUser()).getId()).hasSize(26); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :domain:test --tests '*DB01RepositoriesIT'`

- [ ] **Step 3: Implement** — write `V1__identity.sql` from v1 §4; entities and repositories listed above. `BaseEntity` assigns the id in the constructor-or-`@PrePersist` only if null, so services can set the id before encrypting (ov §3.1).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :domain:test --tests '*DB01RepositoriesIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(domain): V1__identity users, oauth_accounts, organizations, org_members"`

---

## Done when

- [ ] All tasks above are committed on `ticket/db-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
