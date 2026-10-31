# WEB-03 · Command palette wiring + shortcuts

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the command palette with projects, pages and permitted actions.

**Lane:** WEB · **Depends on:** [WEB-02](WEB-02-app-shell.md), [UI-05](UI-05-command-palette.md) · **Size budget:** ~350 changed lines (hard limit 1000) · **Branch:** `ticket/web-03`

**Spec:** ov §4.4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/features/shell/AppCommands.tsx`
- Test: `frontend/src/features/shell/commands.test.tsx`

## Interfaces

- **Produces:**
- Groups: **Go to** (Projects, Members, Settings, each project from the cached `projects.list`), **Actions** on project pages (Trigger run, Dry run, Open editor) shown only if `usePermission` allows, **Org** (Switch org → list).
- Shortcuts `g p` → org projects, `g r` → current project runs, `/` → focus the page's search input (`data-search` attribute).

## Tasks

### Task 1: Command palette wiring + shortcuts

- [ ] **Step 1: Write the failing test**

```tsx
test("palette lists cached projects", async () => { });
test("Trigger run hidden for viewers", async () => { });
test("g r on a project page goes to its runs", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/shell/commands.test.tsx`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/shell/commands.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): command palette wiring + shortcuts"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
