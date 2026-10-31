# WEB-10 · Config form

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The project config form with conditional sections, write-only AI key and read-only mode.

**Lane:** WEB · **Depends on:** [WEB-07](WEB-07-project-overview.md), [UI-02](UI-02-form-controls.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/web-10`

**Spec:** v1 §10.5

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/src/routes/_app/projects/$id/config.tsx`
- Create: `frontend/src/features/config/` — `ConfigForm.tsx`, `sections/{Outputs,Languages,Packages,Versioning,BreakingPolicy,LintPolicy,Ai}Section.tsx`, `schema.ts` (zod mirroring PRJ-04 validation), `api.ts`, `index.ts`
- Test: `frontend/src/features/config/config.test.tsx`

## Interfaces

- **Produces:**
- react-hook-form + zod; `schema.ts` rules equal PRJ-04's (`SDK` needs ≥ 1 language; per-language package fields; `outputDir` relative, ends with `/`). Server field errors (`fieldErrors`) are set on the form.
- Conditional: languages only with SDK; npm fields with typescript, PyPI with python, Maven with java; AI section shows `SecretField(isSet = aiKeySet)` (`null` = keep, `""` via "Remove key").
- Save disabled until dirty; unsaved guard; `?welcome=1` shows a banner "Choose what to generate".
- Without `CONFIG_EDIT`: values as text + note "You have view access".

## Tasks

### Task 1: Config form

- [ ] **Step 1: Write the failing test**

```tsx
test("language fields appear only with SDK", async () => { });
test("python selected requires PyPI name", async () => { });
test("AI key shows Key set and Replace; never pre-filled", async () => { });
test("save disabled until dirty; leaving asks first", async () => { });
test("server field errors shown inline", async () => { });
test("editor sees read-only values", async () => { });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm vitest run src/features/config`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm vitest run src/features/config`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): config form"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-10`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
