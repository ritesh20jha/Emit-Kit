# UI-05 · Command palette

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ⌘K palette and the global keyboard shortcuts, as reusable building blocks.

**Lane:** UI · **Depends on:** [UI-03](UI-03-overlays.md) · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/ui-05`

**Spec:** ov §4.4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/ui/command/CommandPalette.tsx`, `CommandRegistry.tsx` (context + `useCommands`), `ShortcutsProvider.tsx`, `ShortcutsHelp.tsx` + stories
- Test: `frontend/src/ui/command/command.test.tsx`

## Interfaces

- **Produces:**
```ts
type Command = { id: string; group: string; label: string; keywords?: string[]; shortcut?: string; run: () => void };
function useCommands(commands: Command[], deps: unknown[]): void;   // registers while mounted
function useShortcut(keys: string, handler: () => void): void;     // "g p", "/", "?", "mod+k", "mod+s"
```
- Sequence shortcuts (`g p`) with 1 s timeout; ignored while focus is in an input/textarea/CodeMirror, except `mod+s` and `mod+k`.

## Tasks

### Task 1: Command palette

- [ ] **Step 1: Write the failing test**

```tsx
test("mod+k opens, typing filters, Enter runs", async () => {
  const run = vi.fn();
  render(<Harness commands={[{ id: "a", group: "Go", label: "Projects", run }, { id: "b", group: "Go", label: "Members", run: vi.fn() }]} />);
  await userEvent.keyboard("{Control>}k{/Control}");
  await userEvent.type(screen.getByRole("combobox"), "proj");
  expect(screen.getAllByRole("option")).toHaveLength(1);
  await userEvent.keyboard("{Enter}");
  expect(run).toHaveBeenCalled();
});
test("? opens shortcut help", async () => { });
test("g p sequence fires; ignored inside inputs", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/ui/command`

- [ ] **Step 3: Implement** — cmdk-based palette.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/ui/command`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): command palette"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ui-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
