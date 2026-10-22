#!/bin/bash
# Fail when a PR modifies, removes or renames an applied Flyway migration.
#
# Production runs Flyway validate: a changed checksum or a missing applied
# version stops the service from starting. A new version is the only safe
# change, so `added` is the only status that passes; every other one,
# `copied` included, is blocked. Flyway scans db/migration recursively, so
# subdirectories count. Repeatable `R__` migrations are out of scope: there
# are none, and Flyway re-applies them on change by design.
#
# Usage: check-migrations-append-only.sh <pr-files.json> [expected-count]
#   The file is the output of `gh api --paginate repos/<repo>/pulls/<n>/files`:
#   one or more JSON arrays of file entries (filename, status,
#   previous_filename). A rename is checked on both names, so moving a
#   migration out of the directory is caught too. expected-count is the PR's
#   changed_files; the files API stops listing at 3000, so a shorter list
#   fails instead of passing unchecked.
set -euo pipefail

if ! jq -s -e 'length > 0 and all(type == "array")' "$1" >/dev/null 2>&1; then
  echo "::error::$1 is not a PR file list (empty or not JSON arrays)."
  exit 1
fi

listed=$(jq -s 'map(length) | add' "$1")
if [ -n "${2:-}" ] && [ "$listed" -lt "$2" ]; then
  echo "::error::The files API listed $listed of $2 changed files; cannot check the rest."
  exit 1
fi

offending=$(jq -r '
  .[]
  | select(.status != "added")
  | select([.filename, .previous_filename]
           | any(. != null and test("^components-registry-service-server/src/main/resources/db/migration/(.*/)?V[^/]*\\.sql$")))
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
