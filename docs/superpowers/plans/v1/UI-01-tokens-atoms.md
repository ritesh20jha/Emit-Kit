# UI-01 · Tokens, theme, Storybook, base atoms

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The design tokens for both themes, the theme switcher, Storybook with visual snapshots, and the first atoms.

**Lane:** UI · **Depends on:** [T04](T04-frontend-scaffold.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/ui-01`

**Spec:** ov §4.3

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/ui/tokens.css`, `frontend/src/ui/theme/ThemeProvider.tsx`, `useTheme.ts`
- Create: `frontend/src/ui/Button.tsx`, `IconButton.tsx`, `Badge.tsx`, `StatusPill.tsx`, `Kbd.tsx`, `index.ts`, `frontend/src/lib/utils/cn.ts`
- Modify: `frontend/src/styles/app.css` (`@theme` mapping tokens → Tailwind)
- Create: `frontend/.storybook/` (main, preview with a theme toolbar), `*.stories.tsx` per component
- Create: `frontend/e2e/visual/stories.spec.ts` (screenshot every story in light + dark)
- Test: `frontend/src/ui/StatusPill.test.tsx`, `Button.test.tsx`

## Interfaces

- **Produces:**
- Tokens (exact names; values chosen in this PR and reviewed visually): `--color-bg`, `--color-bg-subtle`, `--color-bg-muted`, `--color-fg`, `--color-fg-muted`, `--color-fg-subtle`, `--color-border`, `--color-border-strong`, `--color-accent`, `--color-accent-fg`, `--color-focus`, `--color-status-success`, `--color-status-running`, `--color-status-failed`, `--color-status-awaiting`, `--color-status-neutral`; `--space-1..8` (4 px grid); `--radius-control: 6px`, `--radius-card: 8px`; `--font-sans: Inter`, `--font-mono: "JetBrains Mono"`; type scale `--text-xs: 12px` … `--text-2xl: 24px`, body 14 px; `--duration-fast: 150ms` (0 under `prefers-reduced-motion`).
- Dark theme = same names under `[data-theme="dark"]`; `ThemeProvider` modes `system | light | dark` stored in `localStorage("emitkit.theme")` (try/catch).
- `Button` variants `primary | secondary | ghost | danger`, sizes `sm | md`, `loading` prop (keeps width, sets `aria-busy`).
- `StatusPill status: RunStatus` → icon + text label, colors from status tokens: SUCCESS "Succeeded", RUNNING "Running", FAILED "Failed", AWAITING_APPROVAL "Awaiting approval", CANCELLED "Cancelled", QUEUED "Queued".

## Tasks

### Task 1: Tokens, theme, Storybook, base atoms

- [ ] **Step 1: Write the failing test**

```tsx
test.each([
  ["SUCCESS", "Succeeded"], ["RUNNING", "Running"], ["FAILED", "Failed"],
  ["AWAITING_APPROVAL", "Awaiting approval"], ["CANCELLED", "Cancelled"], ["QUEUED", "Queued"],
])("StatusPill %s shows a text label, not color only", (status, label) => {
  render(<StatusPill status={status as RunStatus} />);
  expect(screen.getByText(label)).toBeVisible();
  expect(screen.getByTestId("status-icon")).toHaveAttribute("aria-hidden", "true");
});
test("loading button keeps its label for screen readers and is busy", () => {
  render(<Button loading>Save</Button>);
  expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("aria-busy", "true");
});
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/ui`

- [ ] **Step 3: Implement** — components per Interfaces; contrast ≥ 4.5:1 for text tokens in both themes (check with axe in the story test). Run `pnpm playwright test e2e/visual` to create the baseline screenshots.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/ui`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): tokens, theme, storybook, base atoms"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ui-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
