#!/usr/bin/env bash
# Prints .env.prod.example with each empty value filled by a random hex string. For smoke.sh and CI
# only: the output is a throwaway env file, never a production one.
# Usage: bash deploy/compose/gen-env.sh > file
set -euo pipefail
dir=$(cd "$(dirname "$0")" && pwd)
while IFS= read -r line; do
  case "$line" in
    *=) printf '%s%s\n' "$line" "$(openssl rand -hex 24)" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$dir/.env.prod.example"
