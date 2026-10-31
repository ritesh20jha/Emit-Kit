# WEB-06 · New-project flow

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The 3-step guided flow to create a project from an existing or new repo.

**Lane:** WEB · **Depends on:** [WEB-05](WEB-05-projects-list.md), [UI-02](UI-02-form-controls.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/web-06`

**Spec:** v1 §10.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/orgs/$orgId/projects/new.tsx`
- Create: `frontend/src/features/new-project/` — `StepRepository.tsx`, `RepoPicker.tsx`, `StepSpec.tsx`, `StepOutput.tsx`, `Summary.tsx`, `schemas.ts`, `api.ts`, `index.ts`
- Test: `frontend/src/features/new-project/flow.test.tsx`

## Interfaces

- **Produces:**
- Search params: `step` (1–3), `mode` (`existing|new`), `repo`, `specPath`, `branch`, `outputMode`, `outputRepo`. Each step validates its own fields with zod before Next.
- Step 1 existing: `RepoPicker` (search, owner, private badge, last pushed); new: name + visibility, name validity checked as you type against `vcs-repos` (taken → inline error).
- Step 2: spec path (default `openapi.yaml`), branch (pre-filled from repo default); existing mode → debounced `spec-check` shows "Found: {title} v{version}" or errors.
- Step 3: output mode; separate → second `RepoPicker`; summary lists what will happen (webhook, starter spec for new repos).
- Finish → POST → navigate `/projects/$id/config?welcome=1`.

## Tasks

### Task 1: New-project flow

- [ ] **Step 1: Write the failing test**

```tsx
test("step is kept in the URL; back and refresh keep it", async () => { });
test("spec-check result shows title and version", async () => { });
test("taken repo name shows inline error", async () => { });
test("separate mode requires output repo", async () => { });
test("finish navigates to config with welcome", async () => { });
test("409 duplicate shows message on step 1", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/new-project`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/new-project`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): new-project flow"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-06`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
