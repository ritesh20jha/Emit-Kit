# PRJ-02 · Create project (new repo)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a new GitHub repo with a starter OpenAPI 3.1 spec and register it as a project.

**Lane:** API · **Depends on:** [PRJ-01](PRJ-01-create-existing.md) · **Size budget:** ~350 changed lines (hard limit 1000) · **Branch:** `ticket/prj-02`

**Spec:** v1 §8 POST projects `mode=NEW`, §10.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/api-app/src/main/java/dev/emitkit/api/projects/ProjectService.java` (`createNew`)
- Create: `backend/api-app/src/main/resources/starter/openapi.yaml.mustache`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/projects/PRJ02CreateNewRepoIT.java`

## Interfaces

- **Produces:**
- `createNew`: `createRepo(owner, name, visibility == PRIVATE)` → `putFile(repo, specPath, starter, null, "Add starter OpenAPI spec (EmitKit)")` → same as `createExisting` from step 3.
- Starter spec: `openapi: 3.1.0`, `info.title` = repo name, `info.version: 0.1.0`, one `GET /health` operation with `operationId: getHealth`, tag `health` — passes every ERROR lint rule.
- Repo name taken (GitHub 422) → `AppException(CONFLICT, "Repository name is taken")`.

## Tasks

### Task 1: Create project (new repo)

- [ ] **Step 1: Write the failing test**

```java
@Test void createsRepoAndStarterSpec() { }
@Test void starterSpecParsesAndHasNoLintErrors() { var r = new SpecParser().parse(starter("pets-api")); assertThat(r.ok()).isTrue(); assertThat(new SpecLinter().lint(r, starter("pets-api"))).noneMatch(i -> i.severity() == ERROR); }
@Test void nameTaken_returns409() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*PRJ02*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*PRJ02*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): create project (new repo)"`

---

## Done when

- [ ] All tasks above are committed on `ticket/prj-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
