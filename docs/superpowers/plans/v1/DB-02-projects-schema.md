# DB-02 · Migration V2: projects + configs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Flyway migration `V2__projects` (projects, project_configs) with JPA entities and Spring Data repositories.

**Lane:** CORE · **Depends on:** [DB-01](DB-01-identity-schema.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/db-02`

**Spec:** v1 §4 (tables, columns, enums, indexes)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/domain/src/main/resources/db/migration/V2__projects.sql`
- Create: entities + repositories under `backend/domain/src/main/java/dev/emitkit/domain/` — `Project` (enum `OutputMode {APPEND, SEPARATE}`), `ProjectConfig` (enums `VersionStrategy {EMITKIT_MANAGED, SPEC_VERSION}`, `BreakingGate {ALLOW, REQUIRE_APPROVAL, BLOCK}`, `LintFailOn {NEVER, ERROR, WARNING}`; `List<OutputKind> outputs` with `OutputKind {SDK, CLI, MCP, DOCS}`, `List<String> sdkLanguages`); repos `ProjectRepository.findByOrgId`, `findByRepoFullName` (all orgs, for webhooks), `existsByOrgIdAndRepoFullName`; `ProjectConfigRepository.findLatest(String projectId)` (`ORDER BY version DESC LIMIT 1`), `nextVersion(projectId)`
- Test: `backend/domain/src/test/java/dev/emitkit/domain/DB02RepositoriesIT.java` (extends `PostgresIT`)

## Interfaces

- **Produces:** the tables exactly as in v1 §4 (column names, `UNIQUE` constraints, defaults). Enums are stored as `varchar` with a `CHECK` constraint and mapped with `@Enumerated(STRING)`. IDs are `varchar(26)` assigned in Java with `Ids.newUlid()` in a `@PrePersist` on `BaseEntity` (DB-01). JSONB columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.
- `project_configs` is append-only: no update methods are exposed by services (enforced in PRJ-04).

## Tasks

### Task 1: Migration V2__projects + entities

- [ ] **Step 1: Write the failing test**

```java
@Test void sameRepoTwiceInOrg_violatesUnique() { /* (org_id, repo_full_name) */ }
@Test void sameRepoInTwoOrgs_isAllowed() { }
@Test void findLatest_returnsHighestVersion() {
  configs.save(config(project, 1)); configs.save(config(project, 2));
  assertThat(configs.findLatest(project.getId())).get().extracting(ProjectConfig::getVersion).isEqualTo(2);
}
@Test void duplicateConfigVersion_violatesUnique() { }
@Test void jsonbListsRoundTrip() {
  var c = config(project, 1); c.setOutputs(List.of(OutputKind.SDK, OutputKind.MCP)); c.setSdkLanguages(List.of("typescript","java"));
  configs.saveAndFlush(c); em.clear();
  assertThat(configs.findById(c.getId())).get().satisfies(r -> {
    assertThat(r.getOutputs()).containsExactly(OutputKind.SDK, OutputKind.MCP);
    assertThat(r.getSdkLanguages()).containsExactly("typescript","java"); });
}
@Test void defaults_applied() { /* output_dir '.emitkit/', breaking_gate ALLOW, lint_fail_on NEVER, ai_provider 'gemini' */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :domain:test --tests '*DB02RepositoriesIT'`

- [ ] **Step 3: Implement** — write `V2__projects.sql` from v1 §4; entities and repositories listed above. Defaults are column defaults in SQL and field initializers in Java (both, so a Java-built entity matches).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :domain:test --tests '*DB02RepositoriesIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(domain): V2__projects projects, project_configs"`

---

## Done when

- [ ] All tasks above are committed on `ticket/db-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
