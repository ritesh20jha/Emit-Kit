# SBX-02 · Toolchain check.sh contract

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define and implement the sandbox input/output contract: what the worker sends in, what check.sh runs, and what comes back.

**Lane:** SBX · **Depends on:** — · **Size budget:** ~200 changed lines (hard limit 1000) · **Branch:** `ticket/sbx-02`

**Spec:** v1 §5.6 (checks per language, single-run mode, report.json)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `deploy/docker/toolchain/check.sh`
- Create: `deploy/docker/toolchain/test/check.bats`, `test/stubs/{tsc,prettier,ruff,javac,google-java-format}` (stub binaries that exit per env var)

## Interfaces

- **Produces:**
- **Input:** `/work/in.tar` containing `src/**` (the generated files, repo-relative paths) and `languages.txt` (one of `typescript`, `python`, `java` per line; `typescript` also covers CLI and MCP dirs).
- **Run:** `check.sh` extracts to `/work/src`, then per language: TS — for each dir containing `tsconfig.json`: `prettier --write .` then `tsc --noEmit --strict -p .`; Python — `ruff format .` then `ruff check --fix .` in each dir containing `pyproject.toml`; Java — `google-java-format --replace` on all `*.java`, then `javac -d /work/classes -cp /opt/vendor/*` per SDK dir. Env `CI=1`, `TSC_WATCHFILE=UseFsEvents` off, no watchers.
- **Output:** `/work/out.tar` (formatted `src/**`), `/work/report.json` = `{"results":[{"tool":"tsc","dir":"sdk/typescript","exitCode":0,"output":"…"}]}` (output capped 64 KB per tool). Exit code 0 even when a tool fails (the report carries failures); non-zero only for infrastructure errors (bad input).

## Tasks

### Task 1: check.sh with stub tools

- [ ] **Step 1: Write the failing test**

```bash
@test "all tools pass → report has zero exit codes" { run_check typescript python java; assert_report_all_zero; }
@test "tsc fails → report records tsc exit 2, script exits 0" { STUB_TSC_EXIT=2 run_check typescript; assert_tool_exit tsc 2; assert_success; }
@test "missing in.tar → script exits 64" { }
@test "output capped at 64KB" { }
@test "formatted files land in out.tar" { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `bats deploy/docker/toolchain/test/check.bats`

- [ ] **Step 3: Implement** — POSIX `sh` + `jq`-free JSON writing (use `printf` with escaping helper); `shellcheck` clean.

- [ ] **Step 4: Run it — expect PASS**

  Run: `bats deploy/docker/toolchain/test/check.bats`

- [ ] **Step 5: Commit** — `git commit -m "feat(toolchain): check.sh sandbox contract"`

---

## Done when

- [ ] All tasks above are committed on `ticket/sbx-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
