# WEB-09 · Conflict merge + spec diff page

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve save conflicts safely, and compare any two spec snapshots.

**Lane:** WEB · **Depends on:** [WEB-08](WEB-08-spec-editor.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/web-09`

**Spec:** v1 §10.4 (409 dialog), §10.1 `/projects/$id/diff`; README Review Focus #4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/features/spec-editor/ConflictDialog.tsx`, `MergeView.tsx`
- Create: `frontend/src/routes/_app/projects/$id/diff.tsx` (lazy)
- Create: `frontend/src/features/spec-diff/` — `SnapshotPicker.tsx`, `DiffSummary.tsx`, `TextDiff.tsx`, `api.ts`, `index.ts`
- Test: `frontend/src/features/spec-editor/conflict.test.tsx`, `frontend/src/features/spec-diff/diff.test.tsx`

## Interfaces

- **Produces:**
- `ConflictDialog`: "Someone else changed this file." **Reload** (discard mine; confirm if dirty) or **Compare** → `MergeView` (`@codemirror/merge`, theirs left read-only, mine right editable) → "Save merged" uses **their** sha (from `extra.currentSha`) so a third conflict is still detected.
- Diff page: search params `from`, `to` (snapshot ids; default last two). `DiffSummary`: breaking changes first (red label "Breaking" + icon), then added, removed, modified; `TextDiff` side-by-side (single column below 768 px).

## Tasks

### Task 1: Conflict merge + spec diff page

- [ ] **Step 1: Write the failing test**

```tsx
test("conflictDialog_offersCompare", async () => {
  server.use(http.put("/api/v1/projects/p1/spec", () => HttpResponse.json({ code: "SPEC_CONFLICT", status: 409, currentSha: "theirs" }, { status: 409 })));
  await saveWithEdits("mine");
  expect(await screen.findByRole("dialog", { name: /someone else changed this file/i })).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Compare" }));
  expect(screen.getByTestId("merge-view")).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Save merged" }));
  expect(lastPutBody().sha).toBe("theirs");
});
test("Reload discards mine after confirming", async () => { });
test("diff page reads from/to from the URL", async () => { });
test("breaking changes listed first", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): conflict merge + spec diff page"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-09`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
