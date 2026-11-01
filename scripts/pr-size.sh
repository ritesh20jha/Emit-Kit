#!/usr/bin/env bash
# PR size gate. Sums added + deleted lines from `git diff --numstat <base>...HEAD`,
# skipping binary files and excluded paths (lockfiles, generated client, exported
# OpenAPI, golden snapshots, generated fixture inputs).
#
# Usage: scripts/pr-size.sh <base-ref>
# Env:   PR_LABELS  comma-separated PR labels; "size-exempt" lets an over-limit PR pass.
# Exit:  0 when counted <= limit or the PR is size-exempt, 1 when over the limit.
set -euo pipefail

limit=1000
base="${1:?usage: pr-size.sh <base-ref>}"

exclude=(
  '(^|/)[^/]*\.lock$'
  '(^|/)pnpm-lock\.yaml$'
  '^frontend/src/lib/api/generated/'
  '^frontend/openapi\.json$'
  '(^|/)src/test/resources/golden/'
  '(^|/)golden-inputs/'
)
grep_args=(-E -q)
for pattern in "${exclude[@]}"; do
  grep_args+=(-e "$pattern")
done

numstat="$(git diff --numstat "$base...HEAD")"

counted=0
while IFS=$'\t' read -r added deleted path; do
  if [ -z "$path" ] || [ "$added" = "-" ]; then
    continue
  fi
  if grep "${grep_args[@]}" <<< "$path"; then
    continue
  fi
  counted=$((counted + added + deleted))
done <<< "$numstat"

echo "counted=$counted limit=$limit"

labels=",${PR_LABELS:-},"
if [ "$counted" -gt "$limit" ]; then
  if [[ "$labels" == *,size-exempt,* ]]; then
    echo "size-exempt label present: limit not enforced"
  else
    echo "PR is over the $limit line limit; split it or add the size-exempt label." >&2
    exit 1
  fi
fi
