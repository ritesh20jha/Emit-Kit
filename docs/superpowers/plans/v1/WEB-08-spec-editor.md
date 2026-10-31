# WEB-08 · Spec editor

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The CodeMirror spec editor with live validation, lint markers, problems panel and save actions.

**Lane:** WEB · **Depends on:** [WEB-07](WEB-07-project-overview.md), [UI-02](UI-02-form-controls.md) · **Size budget:** ~850 changed lines (hard limit 1000) · **Branch:** `ticket/web-08`

**Spec:** v1 §10.4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/projects/$id/editor.tsx` (lazy route)
- Create: `frontend/src/features/spec-editor/` — `SpecEditor.tsx`, `useSpecValidation.ts`, `lintExtension.ts`, `ProblemsPanel.tsx`, `EditorToolbar.tsx`, `useUnsavedGuard.ts`, `api.ts`, `index.ts`
- Test: `frontend/src/features/spec-editor/editor.test.tsx`

## Interfaces

- **Produces:**
- CodeMirror 6 with `@codemirror/lang-yaml` / `lang-json` by `format`, line numbers, folding, search; loaded only on this route (dynamic import).
- Validation: 500 ms debounce → `@apidevtools/swagger-parser.validate` in a Web Worker + `POST /lint`; both mapped to CM diagnostics; Problems panel sorted by severity, click → cursor to line + focus editor.
- Toolbar: Save (`mod+s`), Save & Generate, Dry run (triggers a dry run without saving if clean, else disabled with tooltip "Save first"). "Unsaved changes" indicator. `useUnsavedGuard` uses router `useBlocker` + `beforeunload`.
- On save success: invalidate `qk.projects.spec(id)`; Save & Generate success → toast with link to run; 202 with `runError` → toast with "Retry run" action.
- 409 handling is WEB-09 (this ticket just calls `onConflict(error)`).

## Tasks

### Task 1: Spec editor

- [ ] **Step 1: Write the failing test**

```tsx
test("clicking a problem moves the cursor to its line", async () => { });
test("navigating away with unsaved edits asks first", async () => { });
test("mod+s saves", async () => { });
test("202 runError shows Retry run action", async () => { });
test("lint markers come from POST /lint", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/spec-editor`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/spec-editor`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): spec editor"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-08`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
