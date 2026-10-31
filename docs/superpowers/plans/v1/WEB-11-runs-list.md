# WEB-11 · Runs list

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The project's run list with URL filters and trigger buttons.

**Lane:** WEB · **Depends on:** [WEB-07](WEB-07-project-overview.md), [CON-03](CON-03-contract-runs.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/web-11`

**Spec:** v1 §10.1 `/projects/$id/runs`

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/projects/$id/runs.tsx`
- Create: `frontend/src/features/runs/` — `RunsTable.tsx`, `RunFilters.tsx`, `TriggerButtons.tsx`, `api.ts`, `schemas.ts`, `index.ts`
- Test: `frontend/src/features/runs/list.test.tsx`

## Interfaces

- **Produces:**
- Search params `status`, `trigger`, `cursor`. Columns: status, trigger (+ "Dry run" badge), version, duration, PR link, created (relative, absolute in tooltip).
- Trigger run / Dry run need `RUN_TRIGGER`; success → navigate to `/runs/$runId`.
- Active runs (QUEUED/RUNNING) refetch every 5 s while any is visible.

## Tasks

### Task 1: Runs list

- [ ] **Step 1: Write the failing test**

```tsx
test("filters live in the URL", async () => { });
test("trigger buttons hidden for viewers", async () => { });
test("dry run posts dryRun true", async () => { });
test("polls while a run is active, stops after", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/runs/list.test.tsx`

- [ ] **Step 3: Implement** — as listed. Every data view renders the four states (ov §4.6) using `ui/` `Skeleton`, `EmptyState`, `ErrorState`, `NoAccessState`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/runs/list.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): runs list"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-11`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
