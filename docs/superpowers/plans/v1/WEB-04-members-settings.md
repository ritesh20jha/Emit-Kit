# WEB-04 · Members + org settings

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Members list with inline role editing for admins, and the org settings page with re-sync.

**Lane:** WEB · **Depends on:** [WEB-02](WEB-02-app-shell.md), [CON-01](CON-01-contract-me-orgs.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/web-04`

**Spec:** v1 §10.1 members, settings

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/orgs/$orgId/members.tsx`, `settings.tsx`
- Create: `frontend/src/features/members/` — `MembersTable.tsx`, `RoleSelect.tsx`, `api.ts`, `index.ts`; `frontend/src/features/orgs/OrgSettings.tsx`
- Test: `frontend/src/features/members/members.test.tsx`

## Interfaces

- **Produces:**
- Columns: avatar + login + name, role, source ("GitHub sync" / "Set manually"). Admin: `RoleSelect` inline; change → PATCH → optimistic update, rollback + toast on error (e.g. last admin).
- Settings: slug, GitHub ID (mono + copy), member count, "Re-sync from GitHub" → `POST /me/sync-orgs`, toast "Synced", invalidates `qk.me` + `qk.orgs.all`.

## Tasks

### Task 1: Members + org settings

- [ ] **Step 1: Write the failing test**

```tsx
test("admin changes a role inline", async () => { });
test("last-admin error rolls back and toasts the message", async () => { });
test("non-admin sees roles as text, no selects", async () => { });
test("re-sync shows toast and refetches", async () => { });
test("four states render", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/members`

- [ ] **Step 3: Implement** — as listed. Every data view renders the four states (ov §4.6) using `ui/` `Skeleton`, `EmptyState`, `ErrorState`, `NoAccessState`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/members`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): members + org settings"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
