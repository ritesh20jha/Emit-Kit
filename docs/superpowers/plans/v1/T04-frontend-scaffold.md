# T04 · Frontend scaffold

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the fresh React app with the folder structure, lint rules (import boundaries, no raw palette classes) and test tooling from ov §4.

**Lane:** PLAT · **Depends on:** — · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/t04`

**Spec:** ov §4.2, §4.5, §4.7

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/package.json` (pnpm), `vite.config.ts`, `tsconfig.json` (strict, `noUncheckedIndexedAccess`), `index.html`
- Create: `frontend/src/main.tsx`, `src/app/router.tsx`, `src/app/providers.tsx` (QueryClient), `src/routes/__root.tsx`, `src/routes/index.tsx` (placeholder "EmitKit")
- Create: empty folders with `README.md` one-liners: `src/features/`, `src/ui/`, `src/lib/api/`, `src/lib/auth/`, `src/lib/sse/`, `src/test/`
- Create: `frontend/src/styles/app.css` (Tailwind v4 import only)
- Create: `frontend/eslint.config.js`, `frontend/eslint-rules/no-raw-palette.js`, `frontend/eslint-rules/no-raw-palette.test.ts`
- Create: `frontend/vitest.config.ts`, `src/test/setup.ts` (Testing Library + MSW server), `src/test/msw/server.ts`, `src/routes/index.test.tsx`
- Create: `frontend/playwright.config.ts`, `frontend/e2e/smoke.spec.ts`

## Interfaces

- **Produces:** scripts `pnpm dev`, `pnpm build`, `pnpm lint`, `pnpm vitest run`, `pnpm playwright test`; path alias `@/` → `src/`; MSW `server` export from `src/test/msw/server.ts` for all later tests.
- **Lint rules (exact):**
  - `eslint-plugin-boundaries` element types `routes` (`src/routes/**`), `features` (`src/features/*/**`), `ui` (`src/ui/**`), `lib` (`src/lib/**`); allowed: routes→features|ui|lib, features→ui|lib|features(index only), ui→lib(`lib/utils` only), lib→lib.
  - `emitkit/no-raw-palette`: in files outside `src/ui/`, report string literals/`className` values matching `\b(bg|text|border|ring|fill|stroke|from|to|via)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b` and arbitrary sizes `text-\[\d+px\]`.

## Tasks

### Task 1: Vite + router + query

- [ ] **Step 1: Write the failing smoke test** `src/routes/index.test.tsx`: render the router at `/`, expect `screen.getByText("EmitKit")`.
- [ ] **Step 2: Run — expect FAIL.** Run: `pnpm vitest run src/routes/index.test.tsx`
- [ ] **Step 3: Implement** app scaffold files above (TanStack Router file-based plugin in `vite.config.ts`).
- [ ] **Step 4: Run — expect PASS**; `pnpm build` succeeds.
- [ ] **Step 5: Commit** — `git commit -m "feat(web): scaffold vite react app"`

### Task 2: Lint rules

- [ ] **Step 1: Write `no-raw-palette.test.ts`** with ESLint `RuleTester`:
  - invalid: `<div className="bg-indigo-500" />` in `src/features/x/A.tsx`; `"text-[10px]"`.
  - valid: same code in `src/ui/Button.tsx`; `className="bg-accent text-fg-muted"`.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement the rule** and `eslint.config.js` (typescript-eslint strict, react-hooks, boundaries config above, local plugin `emitkit`).
- [ ] **Step 4: Add a boundaries check** — fixture `src/features/a/internal.ts` imported from `src/features/b/B.tsx` must be reported. Put fixtures under `eslint-rules/__fixtures__/` and lint them in the test with `ESLint.lintFiles`, expecting `boundaries/element-types` errors.
- [ ] **Step 5: Run — expect PASS**; `pnpm lint` clean on `src/`.
- [ ] **Step 6: Commit** — `git commit -m "chore(web): lint rules for boundaries and design tokens"`

### Task 3: Playwright smoke

- [ ] **Step 1:** `e2e/smoke.spec.ts` opens `/` against `pnpm preview` (webServer in config) and expects the heading.
- [ ] **Step 2: Run** `pnpm playwright test` — PASS.
- [ ] **Step 3: Commit** — `git commit -m "test(web): playwright smoke test"`

---

## Done when

- [ ] All tasks above are committed on `ticket/t04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
