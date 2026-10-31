# WEB-13 · Run page: header, timeline, tabs, approval

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The run page: header and actions, step timeline, child routes for logs, files, lint and changes, and the approval banner.

**Lane:** WEB · **Depends on:** [WEB-12](WEB-12-live-logs.md), [WEB-11](WEB-11-runs-list.md) · **Size budget:** ~850 changed lines (hard limit 1000) · **Branch:** `ticket/web-13`

**Spec:** v1 §10.6

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/runs/$runId.tsx` (layout), `$runId/logs.tsx` (index), `$runId/files.tsx`, `$runId/lint.tsx`, `$runId/changes.tsx`
- Create: `frontend/src/features/run/` — `RunHeader.tsx`, `RunActions.tsx`, `StepTimeline.tsx`, `ApprovalBanner.tsx`, `FilesTree.tsx`, `FileDiff.tsx`, `LintTable.tsx`, `ChangesSummary.tsx`, `api.ts`, `index.ts`
- Test: `frontend/src/features/run/run.test.tsx`

## Interfaces

- **Produces:**
- Header per v1 §10.6; short sha 7 chars + `CopyButton`; actions: Cancel (QUEUED/RUNNING/AWAITING_APPROVAL + `RUN_CANCEL`, `ConfirmDialog`), Rerun (terminal + `RUN_RERUN`), Download zip (artifact exists) → `/api/v1/runs/$runId/artifact`.
- `StepTimeline`: 11 steps from `RunDetail.steps`, horizontal ≥ 768 px else vertical; clicking a step sets `?step=` on the logs route.
- Files: tree from manifest with status badges; `?path=` selects a file → `FileDiff` (unified diff rendered with `@codemirror/merge` unified view, lazy).
- Approval banner when `AWAITING_APPROVAL`: lists breaking changes; with `RUN_APPROVE`: comment textarea + Approve / Reject; others see "Waiting for an admin to approve".
- While the run is active, `RunDetail` refetches on each step change seen in the log stream (and every 10 s as a fallback).

## Tasks

### Task 1: Run page: header, timeline, tabs, approval

- [ ] **Step 1: Write the failing test**

```tsx
test("clicking a step filters logs", async () => { });
test("actions follow status and permission", async () => { });
test("admin approves with comment", async () => { });
test("editor sees waiting message, no buttons", async () => { });
test("files tab selects file from ?path", async () => { });
test("changes tab lists breaking first", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/run`

- [ ] **Step 3: Implement** — as listed. Keep each route file ≤ 150 lines.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/run`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): run page: header, timeline, tabs, approval"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-13`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
