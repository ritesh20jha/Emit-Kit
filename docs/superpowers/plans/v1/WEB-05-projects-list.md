# WEB-05 · Projects list

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The org's searchable, filterable projects list.

**Lane:** WEB · **Depends on:** [WEB-02](WEB-02-app-shell.md), [CON-02](CON-02-contract-projects.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/web-05`

**Spec:** v1 §10.1 `/orgs/$orgId`

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/orgs/$orgId/index.tsx`
- Create: `frontend/src/features/projects/` — `ProjectsList.tsx`, `ProjectFilters.tsx`, `api.ts`, `schemas.ts` (zod search params), `index.ts`
- Test: `frontend/src/features/projects/list.test.tsx`

## Interfaces

- **Produces:**
- Search params (zod-validated): `q`, `status` (last run status), `cursor`. Debounced 300 ms search writes `q` to the URL (`replace: true`).
- Row: repo (mono), last run `StatusPill` + relative time, current version, link to `/projects/$id`.
- "New project" button only with `PROJECT_CREATE`; empty state "No projects yet. Connect a repository to start." → new-project link (or "Ask an admin or editor" for viewers).

## Tasks

### Task 1: Projects list

- [ ] **Step 1: Write the failing test**

```tsx
test("search writes q to the URL and filters", async () => { });
test("status filter survives reload", async () => { });
test("empty state links to new project", async () => { });
test("viewer does not see New project", async () => { });
test("pagination loads next cursor", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/projects/list.test.tsx`

- [ ] **Step 3: Implement** — as listed. Every data view renders the four states (ov §4.6) using `ui/` `Skeleton`, `EmptyState`, `ErrorState`, `NoAccessState`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/projects/list.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): projects list"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
