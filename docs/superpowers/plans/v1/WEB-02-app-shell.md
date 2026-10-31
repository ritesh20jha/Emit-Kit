# WEB-02 · App shell + login

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Login page, auth guard, sidebar with org switcher, top bar and mobile drawer.

**Lane:** WEB · **Depends on:** [WEB-01](WEB-01-api-auth-layer.md), [UI-03](UI-03-overlays.md), [UI-04](UI-04-data-display.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/web-02`

**Spec:** ov §4.4 app shell; v1 §10.1 (`/login`, `/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/login.tsx`, `frontend/src/routes/_app.tsx` (guard layout; `beforeLoad` ensures `meQuery`), `frontend/src/routes/_app/index.tsx` (redirect)
- Create: `frontend/src/features/shell/` — `Sidebar.tsx`, `OrgSwitcher.tsx`, `TopBar.tsx`, `QuotaIndicator.tsx`, `UserMenu.tsx`, `MobileDrawer.tsx`, `lastOrg.ts`, `index.ts`
- Test: `frontend/src/features/shell/shell.test.tsx`

## Interfaces

- **Produces:**
- `/login`: one "Sign in with GitHub" button → `window.location = "/oauth2/authorization/github?next=" + encodeURIComponent(next)`.
- `/` → `lastOrg.get()` (localStorage `emitkit.lastOrg`, try/catch) if still in `me.orgs`, else personal org → `/orgs/$orgId`.
- Sidebar: org switcher (search when > 7 orgs), nav Projects / Members / Settings for the current org (from route params, or last org on project/run pages via the project's `orgId`).
- `QuotaIndicator`: polls `/me/vcs-quota` every 60 s; hidden when `remaining/limit ≥ 0.2`; shows "GitHub quota low: resets at HH:MM" otherwise.
- Below 768 px the sidebar is a `Sheet` opened from a menu button.

## Tasks

### Task 1: App shell + login

- [ ] **Step 1: Write the failing test**

```tsx
test("unauthenticated user is sent to login with next", async () => { });
test("root redirects to last used org, falls back to personal", async () => { });
test("switching org updates the URL and remembers it", async () => { });
test("quota indicator hidden at 50%, shown at 10%", async () => { });
test("mobile drawer at 360px opens and traps focus", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/shell`

- [ ] **Step 3: Implement** — as listed. Every data view renders the four states (ov §4.6) using `ui/` `Skeleton`, `EmptyState`, `ErrorState`, `NoAccessState`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/shell`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): app shell + login"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
