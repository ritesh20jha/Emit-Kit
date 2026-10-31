# CUT-01 · Delete the TypeScript code (size-exempt)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the old Bun/TypeScript app once the new stack passes the parity checklist.

**Lane:** OPS · **Depends on:** [WEB-15](WEB-15-e2e-parity.md), [WRK-12](WRK-12-pipeline-e2e.md), [OPS-02](OPS-02-backups.md), [OPS-04](OPS-04-load-security.md) · **Size budget:** ~(deletions; size-exempt) changed lines (hard limit 1000) · **Branch:** `ticket/cut-01`

**Spec:** v1 §1 exit criteria, §12

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Delete: `apps/`, `packages/`, `bun.lock`, `bunfig.toml`, `turbo.json`, `bts.jsonc`, root `package.json`, root `tsconfig.json`, `start-prod.sh`, old `.github/workflows/ci.yml`
- Modify: `README.md` (new stack, quick start with compose, links to specs and runbooks; ≤ 300 lines)
- Modify: `scripts/pr-size.sh` callers — none; this PR carries label `size-exempt`

## Interfaces

- **Precondition:** every v1 §12 checklist line is ticked in the PR description with a link to the passing WEB-15 test, and a human has signed off.

## Tasks

### Task 1: Delete and verify

- [ ] **Step 1:** `git rm -r` the paths above.
- [ ] **Step 2:** `grep -rn "apps/web\|packages/\|bun " --exclude-dir=docs .` returns nothing outside `docs/`.
- [ ] **Step 3:** Rewrite `README.md`. The README is the only added content; keep it ≤ 300 lines and list its line count in the PR description.
- [ ] **Step 4:** CI green (backend, frontend, compose, e2e).
- [ ] **Step 5: Commit** — `git commit -m "chore: remove the TypeScript implementation"`; open the PR with label `size-exempt`.

---

## Done when

- [ ] All tasks above are committed on `ticket/cut-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
