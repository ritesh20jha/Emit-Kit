# UI-04 · Data display & states

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tables, mobile lists, the four data-view states, code blocks and route-aware tabs.

**Lane:** UI · **Depends on:** [UI-01](UI-01-tokens-atoms.md) · **Size budget:** ~750 changed lines (hard limit 1000) · **Branch:** `ticket/ui-04`

**Spec:** ov §4.3, §4.4 (tabs are routes), §4.6

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/ui/Table.tsx`, `DataList.tsx`, `ResponsiveTable.tsx`, `Skeleton.tsx`, `EmptyState.tsx`, `ErrorState.tsx`, `NoAccessState.tsx`, `CodeBlock.tsx`, `CopyButton.tsx`, `PageHeader.tsx`, `Breadcrumbs.tsx`, `RouteTabs.tsx` + stories
- Test: `frontend/src/ui/data.test.tsx`

## Interfaces

- **Produces:**
- `ResponsiveTable<T>({ columns, rows, rowKey, renderCard })` → `Table` at ≥ 768 px, `DataList` of cards below (matchMedia hook, SSR-safe).
- `ErrorState({ error: AppError, onRetry })` shows `error.message`, `error.code` in mono, Retry button.
- `NoAccessState({ action, admins? })` → "You need admin access to {action}. Ask an org admin." 
- `RouteTabs({ tabs: { to, label }[] })` renders TanStack `Link`s with `aria-current="page"` on the active one (exact match), keyboard arrows move focus.
- `CopyButton({ value, label })` announces "Copied" via a polite live region.

## Tasks

### Task 1: Data display & states

- [ ] **Step 1: Write the failing test**

```tsx
test("RouteTabs marks the active route", () => {
  renderAt("/projects/p1/runs", <RouteTabs tabs={[{ to: "/projects/p1", label: "Overview" }, { to: "/projects/p1/runs", label: "Runs" }]} />);
  expect(screen.getByRole("link", { name: "Runs" })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute("aria-current");
});
test("ErrorState shows code and retries", async () => { });
test("ResponsiveTable renders cards below 768px", () => { setViewport(375); });
test("CopyButton announces Copied", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/ui/data.test.tsx`

- [ ] **Step 3: Implement** — components per Interfaces.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/ui/data.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): data display & states"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ui-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
