# OPS-04 · Load test + security checks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encode the v1 performance targets as k6 thresholds and add dependency scanning to CI.

**Lane:** OPS · **Depends on:** [WRK-12](WRK-12-pipeline-e2e.md), [OPS-01](OPS-01-prod-compose.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/ops-04`

**Spec:** v1 §11 (latency, run duration, throughput, security scanning)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `perf/k6/api.js`, `perf/k6/runs.js`, `perf/README.md`
- Create: `.github/workflows/security.yml` (OWASP dependency-check Gradle plugin with `failBuildOnCVSS=7`, `pnpm audit --audit-level high`)
- Modify: `backend/build.gradle.kts` (dependency-check plugin)

## Interfaces

- **Produces:**
- `api.js`: 50 VUs over seeded data, non-VCS endpoints (`/projects/{id}`, `/runs`, `/runs/{id}`, `/config`); threshold `http_req_duration{group:non-vcs}: p(95)<300`.
- `runs.js`: 8 concurrent dry runs of `big-200-ops` across 2 workers; threshold run duration `p(95)<180000` (polls `/runs/{id}`).

## Tasks

### Task 1: Load test + security checks

- [ ] **Step 1: Write the failing test**

```js
// perf/k6/api.js
export const options = { thresholds: { "http_req_duration{group:::non-vcs}": ["p(95)<300"], http_req_failed: ["rate<0.01"] } };
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `k6 run perf/k6/api.js && k6 run perf/k6/runs.js`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `k6 run perf/k6/api.js && k6 run perf/k6/runs.js`

- [ ] **Step 5: Commit** — `git commit -m "ops: load test + security checks"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ops-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
