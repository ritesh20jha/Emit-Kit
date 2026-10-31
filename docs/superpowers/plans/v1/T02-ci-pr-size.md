# T02 · CI pipeline + PR size gate

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run backend and frontend builds on every PR and block PRs over 1000 counted lines unless labelled size-exempt.

**Lane:** PLAT · **Depends on:** [T01](T01-backend-skeleton.md), [T04](T04-frontend-scaffold.md) · **Size budget:** ~300 changed lines (hard limit 1000) · **Branch:** `ticket/t02`

**Spec:** ov §6 (CI); README Global Constraints (PR size rule)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `scripts/pr-size.sh`, `scripts/pr-size.test.sh`
- Create: `.github/workflows/backend.yml`, `.github/workflows/frontend.yml`, `.github/workflows/pr-size.yml`
- Modify: none (old `.github/workflows/ci.yml` stays until CUT-01)

## Interfaces

- **Produces:** `scripts/pr-size.sh <base-ref>` → prints `counted=<n> limit=1000` and exits `1` when over the limit; reads labels from env `PR_LABELS` (comma-separated). Exclusion regexes (exact):
  `(^|/)[^/]*\.lock$`, `(^|/)pnpm-lock\.yaml$`, `^frontend/src/lib/api/generated/`, `^frontend/openapi\.json$`, `/src/test/resources/golden/`, `/golden-inputs/`.
  Binary files (`-` in numstat) are ignored.

## Tasks

### Task 1: PR size script

- [ ] **Step 1: Write the failing test** — `scripts/pr-size.test.sh` creates a temp git repo, commits a base, then for each case commits on a branch and calls the script:

```bash
case_lines 999 "src/a.java"            ; expect_exit 0
case_lines 1001 "src/a.java"           ; expect_exit 1
case_lines 5000 "frontend/openapi.json"; expect_exit 0
case_lines 600 "src/a.java" + 600 "src/test/resources/golden/x/out.ts" ; expect_exit 0
PR_LABELS="size-exempt,docs" case_lines 3000 "src/a.java" ; expect_exit 0
```
- [ ] **Step 2: Run — expect FAIL** (script missing). Run: `bash scripts/pr-size.test.sh`
- [ ] **Step 3: Implement `scripts/pr-size.sh`** with `git diff --numstat "$1"...HEAD`, filter with `grep -Ev` on the exclusion list, sum columns 1+2 with awk.
- [ ] **Step 4: Run — expect PASS**, then `shellcheck scripts/*.sh` clean.
- [ ] **Step 5: Commit** — `git commit -m "ci: add PR size gate script"`

### Task 2: Workflows

- [ ] **Step 1: Write workflows.**
  - `backend.yml`: on `pull_request` + `push: main`, paths `backend/**`; `actions/setup-java` (temurin 21), `gradle/actions/setup-gradle`, run `./gradlew build` in `backend/`.
  - `frontend.yml`: paths `frontend/**`; `pnpm/action-setup`, `actions/setup-node` (20), `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm vitest run`, `pnpm build`.
  - `pr-size.yml`: on `pull_request` (opened, synchronize, labeled, unlabeled); checkout with `fetch-depth: 0`; run `scripts/pr-size.sh origin/${{ github.base_ref }}` with `PR_LABELS: ${{ join(github.event.pull_request.labels.*.name, ',') }}`; also runs `scripts/pr-size.test.sh`.
- [ ] **Step 2: Verify** — `actionlint .github/workflows/*.yml` passes.
- [ ] **Step 3: Commit** — `git commit -m "ci: backend, frontend and pr-size workflows"`

---

## Done when

- [ ] All tasks above are committed on `ticket/t02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
