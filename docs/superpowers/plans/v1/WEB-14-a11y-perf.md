# WEB-14 · Accessibility, mobile, performance pass

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit every v1 route for accessibility, mobile layout and bundle size, and fix what fails.

**Lane:** WEB · **Depends on:** [WEB-13](WEB-13-run-page.md), [WEB-10](WEB-10-config-form.md), [WEB-09](WEB-09-conflict-diff.md), [WEB-04](WEB-04-members-settings.md), [WEB-06](WEB-06-new-project.md), [WEB-03](WEB-03-palette-wiring.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/web-14`

**Spec:** ov §4.6, §4.7

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/e2e/a11y.spec.ts`, `frontend/e2e/mobile.spec.ts`
- Create: `frontend/scripts/check-bundle.mjs` (initial JS gzip < 250 KB) + CI step
- Modify: components found by the audit (fixes only)

## Interfaces

- **Produces:**
- Runs against `pnpm preview` with MSW in the browser (`VITE_MSW=1`) so it needs no backend.
- Routes: every route in v1 §10.1 + run child routes, each in light and dark.

## Tasks

### Task 1: Accessibility, mobile, performance pass

- [ ] **Step 1: Write the failing test**

```tsx
test.describe("a11y", () => {
  for (const route of V1_ROUTES) for (const theme of ["light", "dark"]) test(`${route} ${theme}`, async ({ page }) => {
    await page.goto(route + `?theme=${theme}`);
    const r = await new AxeBuilder({ page }).analyze();
    expect(r.violations.filter(v => ["serious", "critical"].includes(v.impact!))).toEqual([]);
  });
});
test("no horizontal scroll at 360px on every route", async ({ page }) => { await page.setViewportSize({ width: 360, height: 740 }); /* scrollWidth <= clientWidth */ });
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm playwright test e2e/a11y.spec.ts e2e/mobile.spec.ts`

- [ ] **Step 3: Implement** — fix violations found; record anything deferred as a follow-up ticket in the PR description.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm playwright test e2e/a11y.spec.ts e2e/mobile.spec.ts`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): accessibility, mobile, performance pass"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-14`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
