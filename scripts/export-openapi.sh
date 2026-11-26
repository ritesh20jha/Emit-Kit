#!/usr/bin/env bash
# Rewrites frontend/openapi.json from the API contract that api-app serves.
#
# The export is OpenApiSnapshotTest in update mode: it boots api-app against the Testcontainers
# PostgreSQL and Redis (so Docker must be running), fetches /v3/api-docs and writes the normalized
# document (sorted keys, two-space indent, trailing newline). The snapshot check uses the same
# normalizer, so the export and the check cannot disagree. Run frontend `pnpm gen:api` afterwards.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$root/backend"
# cleanTest forces the test to run again; Gradle would otherwise treat it as up to date.
./gradlew :api-app:cleanTest :api-app:test \
  --tests 'dev.emitkit.api.OpenApiSnapshotTest' \
  -Dopenapi.update=true

echo "Wrote $root/frontend/openapi.json"
