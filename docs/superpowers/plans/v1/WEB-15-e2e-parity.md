# WEB-15 · E2E parity suite

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Playwright test per parity checklist line against the real stack with a mocked GitHub.

**Lane:** WEB · **Depends on:** [WEB-14](WEB-14-a11y-perf.md), [WRK-12](WRK-12-pipeline-e2e.md) · **Size budget:** ~800 changed lines (hard limit 1000) · **Branch:** `ticket/web-15`

**Spec:** v1 §12 parity checklist

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `frontend/e2e/parity/*.spec.ts` (one file per checklist line)
- Create: `deploy/compose/docker-compose.e2e.yml` (app stack + WireMock GitHub with the stateful mappings from WRK-12's `GitHubMock`, exported as JSON)
- Create: `.github/workflows/e2e.yml` (nightly + before CUT-01)

## Interfaces

- **Produces:**
- Login uses WireMock's OAuth endpoints (api-app `e2e` profile points the GitHub provider there).
- Each test is named after its checklist line so the checklist can be ticked from the report.

## Tasks

### Task 1: E2E parity suite

- [ ] **Step 1: Write the failing test**

```tsx
test("GitHub login, org sync, personal org, org switcher", async ({ page }) => { });
test("create project from existing repo, and with a new repo + starter spec", async ({ page }) => { });
test("webhook registered on create and deleted on project delete", async ({ page }) => { });
// … one per v1 §12 line, plus one per v1 🆕 feature
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `pnpm playwright test e2e/parity`

- [ ] **Step 3: Implement** — the specs.

- [ ] **Step 4: Run it — expect PASS**

  Run: `pnpm playwright test e2e/parity`

- [ ] **Step 5: Commit** — `git commit -m "feat(web): e2e parity suite"`

---

## Done when

- [ ] All tasks above are committed on `ticket/web-15`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
