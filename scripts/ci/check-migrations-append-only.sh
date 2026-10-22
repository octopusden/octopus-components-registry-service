#!/bin/bash
# Fail when a PR modifies, removes or renames an applied Flyway migration.
#
# Production runs Flyway validate: a changed checksum or a missing applied
# version stops the service from starting. A new version is the only safe
# change, so `added` is the only status that passes.
#
# Usage: check-migrations-append-only.sh <pr-files.json>
#   The file is the output of `gh api --paginate repos/<repo>/pulls/<n>/files`:
#   one or more JSON arrays of file entries (filename, status,
#   previous_filename). A rename is checked on both names, so moving a
#   migration out of the directory is caught too.
set -euo pipefail

offending=$(jq -r '
  .[]
  | select(.status == "modified" or .status == "removed" or .status == "renamed")
  | select([.filename, .previous_filename]
           | any(. != null and test("^components-registry-service-server/src/main/resources/db/migration/V[^/]*\\.sql$")))
  | "\(.status)\t\(.previous_filename // "-") -> \(.filename)"
' "$1")

if [ -z "$offending" ]; then
  echo "ok — no applied migration changed."
  exit 0
fi

echo "$offending" >&2
cat >&2 <<'MSG'

Applied migrations are immutable: production runs Flyway validate, and an edited,
removed or renamed version stops the service from starting. Revert the change and
add the correction as the next V<n>__ migration instead.
MSG
echo "::error::A PR may only add Flyway migrations; it changed an existing one."
exit 1
