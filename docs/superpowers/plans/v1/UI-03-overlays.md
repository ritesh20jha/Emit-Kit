# UI-03 · Overlays & feedback

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dialogs, confirmation with typed text, sheet, menus, tooltips and toasts.

**Lane:** UI · **Depends on:** [UI-01](UI-01-tokens-atoms.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/ui-03`

**Spec:** ov §4.3, §4.6 (focus trap)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/ui/Dialog.tsx`, `ConfirmDialog.tsx`, `Sheet.tsx`, `DropdownMenu.tsx`, `Tooltip.tsx`, `Toaster.tsx` (sonner) + `toast.ts` + stories
- Test: `frontend/src/ui/overlays.test.tsx`

## Interfaces

- **Produces:**
- `ConfirmDialog({ title, description, confirmLabel, tone: "default" | "danger", typedConfirmation?: string, onConfirm: () => Promise<void> })` — confirm disabled until the typed text equals `typedConfirmation` exactly; shows loading while `onConfirm` runs; closes on success, stays open and shows the error on failure.
- `toast.success / toast.error(appError)` — error toasts show the message and the mono `code`.

## Tasks

### Task 1: Overlays & feedback

- [ ] **Step 1: Write the failing test**

```tsx
test("dialog traps focus and returns it on close", async () => { });
test("Escape closes the dialog", async () => { });
test("typed confirmation enables the button only on exact match", async () => {
  render(<ConfirmDialog open title="Delete project" typedConfirmation="acme/api" confirmLabel="Delete" onConfirm={vi.fn()} />);
  const confirm = screen.getByRole("button", { name: "Delete" });
  expect(confirm).toBeDisabled();
  await userEvent.type(screen.getByRole("textbox"), "acme/ap");
  expect(confirm).toBeDisabled();
  await userEvent.type(screen.getByRole("textbox"), "i");
  expect(confirm).toBeEnabled();
});
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/ui/overlays.test.tsx`

- [ ] **Step 3: Implement** — Radix primitives restyled with tokens.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/ui/overlays.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): overlays & feedback"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ui-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
