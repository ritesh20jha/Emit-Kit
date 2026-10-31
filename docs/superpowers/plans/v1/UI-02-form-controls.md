# UI-02 · Form controls

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accessible form inputs that plug into react-hook-form, including the write-only secret field.

**Lane:** UI · **Depends on:** [UI-01](UI-01-tokens-atoms.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/ui-02`

**Spec:** ov §4.3 component list, §4.6

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/ui/Input.tsx`, `Textarea.tsx`, `Select.tsx` (Radix), `Checkbox.tsx`, `Switch.tsx`, `Label.tsx`, `FieldError.tsx`, `FormField.tsx`, `SecretField.tsx` + stories
- Test: `frontend/src/ui/forms.test.tsx`

## Interfaces

- **Produces:**
- `FormField<T>({ name, label, description?, children })` reads `useFormContext()`; wires `id`, `htmlFor`, `aria-invalid`, `aria-describedby` (description + error ids).
- `SecretField({ isSet, onReplace, ...inputProps })`: when `isSet` and not replacing → shows "Key set" + **Replace** button (no input in the DOM); Replace → empty password input focused; Cancel returns to "Key set" and clears the field value to `null` (meaning keep).

## Tasks

### Task 1: Form controls

- [ ] **Step 1: Write the failing test**

```tsx
test("error is linked to the input", async () => {
  renderForm(<FormField name="pypiName" label="PyPI name"><Input /></FormField>, { errors: { pypiName: "Required" } });
  const input = screen.getByLabelText("PyPI name");
  expect(input).toHaveAttribute("aria-invalid", "true");
  expect(input).toHaveAccessibleDescription(/Required/);
});
test("SecretField hides the value until Replace", async () => {
  render(<SecretField isSet label="AI key" />);
  expect(screen.queryByLabelText("AI key")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Replace" }));
  expect(screen.getByLabelText("AI key")).toHaveFocus();
});
test("Switch toggles with Space", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/ui/forms.test.tsx`

- [ ] **Step 3: Implement** — components per Interfaces.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/ui/forms.test.tsx`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): form controls"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ui-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
