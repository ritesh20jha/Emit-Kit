# WEB-07 · Project overview, settings, versions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Project layout with tabs, the overview cards, the settings page and version history.

**Lane:** WEB · **Depends on:** [WEB-05](WEB-05-projects-list.md), [CON-03](CON-03-contract-runs.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/web-07`

**Spec:** v1 §10.3, §10.1 (`/projects/$id`, `/settings`, `/versions`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/projects/$id.tsx` (layout + `RouteTabs`: Overview, Runs, Editor, Diff, Config, Versions, Settings), `$id/index.tsx`, `$id/settings.tsx`, `$id/versions.tsx`
- Create: `frontend/src/features/project-overview/` — `LastRunCard.tsx`, `VersionCard.tsx`, `SpecCard.tsx`, `HealthCard.tsx`, `RecentRuns.tsx`, `index.ts`; `frontend/src/features/project-settings/` — `WebhookPanel.tsx`, `DangerZone.tsx`; `frontend/src/features/versions/VersionsList.tsx`
- Test: `frontend/src/features/project-overview/overview.test.tsx`, `frontend/src/features/project-settings/settings.test.tsx`

## Interfaces

- **Produces:**
- Overview cards as v1 §10.3; Trigger run / Rerun need `RUN_TRIGGER` / `RUN_RERUN`.
- Settings: webhook status + "Re-register" (`CONFIG_EDIT`); output mode (read-only display in v1); delete via `ConfirmDialog` with `typedConfirmation = repoFullName` (`PROJECT_DELETE`), then navigate to the org projects list with a toast.
- Versions: version (mono), change type badge, date, run link; row expands to the changelog (markdown rendered as plain text blocks — no HTML injection).

## Tasks

### Task 1: Project overview, settings, versions

- [ ] **Step 1: Write the failing test**

```tsx
test("overview shows last run, version, spec, health and recent runs", async () => { });
test("trigger run posts and navigates to the run", async () => { });
test("delete needs typed confirmation", async () => { });
test("versions row expands changelog", async () => { });
test("tabs are links with aria-current", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/project-overview`

- [ ] **Step 3: Implement** — as listed. Every data view renders the four states (ov §4.6) using `ui/` `Skeleton`, `EmptyState`, `ErrorState`, `NoAccessState`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/project-overview`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): project overview, settings, versions"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-07`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
