#!/usr/bin/env bash
# Tests for scripts/pr-size.sh. Builds a throwaway git repo with a base branch,
# then for each case commits changes on a fresh branch, runs the script against
# base and checks the exit code (and the counted line, when given).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/pr-size.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

repo="$tmp/repo"
git init -q -b base "$repo"

g() {
  git -C "$repo" -c user.name="pr-size-test" -c user.email="pr-size-test@example.invalid" \
    -c commit.gpgsign=false "$@"
}

mkdir -p "$repo/src"
seq 1 40 > "$repo/src/old.java"
printf 'base\n' > "$repo/README.md"
g add -A
g commit -q -m "base"

pass=0
fail=0
seq_no=0
case_desc=""
last_exit=""
last_out=""

# start_branch: new case branch off base.
start_branch() {
  seq_no=$((seq_no + 1))
  g checkout -q -B "case-$seq_no" base
}

# run_case: commit the working tree on the current branch, then run the script
# with base as the base ref. Records its exit code and stdout; stderr passes through.
run_case() {
  g add -A
  g commit -q -m "case $seq_no"
  if last_out="$(cd "$repo" && PR_LABELS="${PR_LABELS:-}" "$script" base)"; then
    last_exit=0
  else
    last_exit=$?
  fi
}

# case_lines <lines> <path> [<lines> <path> ...]: write each file with that many
# lines, commit them on a new branch and run the script.
case_lines() {
  local desc="" n path
  start_branch
  while [ "$#" -ge 2 ]; do
    n="$1"
    path="$2"
    shift 2
    mkdir -p "$repo/$(dirname "$path")"
    seq 1 "$n" > "$repo/$path"
    desc="${desc:+$desc + }$n lines $path"
  done
  case_desc="$desc${PR_LABELS:+ [PR_LABELS=$PR_LABELS]}"
  run_case
}

# expect_exit <code> [<counted>]: check the last run. When <counted> is given the
# output must be exactly "counted=<counted> limit=1000".
expect_exit() {
  local want_exit="$1" want_counted="${2:-}" ok=1 detail=""
  if [ "$last_exit" != "$want_exit" ]; then
    ok=0
    detail="exit $last_exit, want $want_exit"
  fi
  if [ -n "$want_counted" ] && [ "$last_out" != "counted=$want_counted limit=1000" ]; then
    ok=0
    detail="${detail:+$detail; }output '$last_out', want counted=$want_counted limit=1000"
  fi
  if [ "$ok" = 1 ]; then
    pass=$((pass + 1))
    echo "PASS: $case_desc (exit $last_exit)"
  else
    fail=$((fail + 1))
    echo "FAIL: $case_desc: $detail"
  fi
}

# Limit boundary.
case_lines 999 "src/a.java"             ; expect_exit 0 999
case_lines 1000 "src/a.java"            ; expect_exit 0 1000
case_lines 1001 "src/a.java"            ; expect_exit 1 1001

# Excluded paths.
case_lines 5000 "frontend/openapi.json" ; expect_exit 0 0
case_lines 600 "src/a.java" 600 "src/test/resources/golden/x/out.ts" ; expect_exit 0 600
case_lines 1000 "pnpm-lock.yaml" 1000 "frontend/src/lib/api/generated/client.ts" \
  1000 "frontend/openapi.json" 1000 "backend/src/test/resources/golden/a.txt" \
  1000 "tools/golden-inputs/in.json" 1000 "packages/x/Cargo.lock" 1000 "apps/web/yarn.lock" ; expect_exit 0 0

# Near misses: must still be counted.
case_lines 1001 "src/pnpm-lock.yaml.bak" ; expect_exit 1 1001

# Labels: exact match among comma-separated items only.
PR_LABELS="size-exempt,docs" case_lines 3000 "src/a.java" ; expect_exit 0 3000
PR_LABELS="docs,size-exempt" case_lines 1001 "src/a.java" ; expect_exit 0 1001
PR_LABELS="size-exemptions" case_lines 1001 "src/a.java" ; expect_exit 1 1001

# Binary files are ignored.
start_branch
head -c 4096 /dev/zero > "$repo/assets-blob.bin"
seq 1 999 > "$repo/src/a.java"
case_desc="4096-byte binary assets-blob.bin + 999 lines src/a.java"
run_case
expect_exit 0 999

# Renames must not crash the script (numstat shows rename paths).
start_branch
g mv src/old.java src/new.java
printf 'extra\n' >> "$repo/src/new.java"
case_desc="rename src/old.java -> src/new.java with one added line"
run_case
expect_exit 0 1

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
