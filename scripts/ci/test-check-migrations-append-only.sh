#!/bin/bash
# Self-test for check-migrations-append-only.sh against crafted PR file lists.
set -euo pipefail

CHECK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migrations-append-only.sh"
DIR=components-registry-service-server/src/main/resources/db/migration
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
failures=0

# expect <pass|fail> <name> <json> [expected-count]
expect() {
  printf '%s' "$3" >"$tmp"
  if "$CHECK" "$tmp" ${4:+"$4"} >/dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (expected $1, got $got)"; failures=$((failures + 1)); fi
}

expect fail "modified migration" "[{\"filename\":\"$DIR/V11__add_test_component.sql\",\"status\":\"modified\"}]"
expect fail "renamed within the directory" "[{\"filename\":\"$DIR/V10__renamed.sql\",\"previous_filename\":\"$DIR/V10__add_distribution_generic_artifacts.sql\",\"status\":\"renamed\"}]"
expect fail "moved out of the directory" "[{\"filename\":\"docs/V9__add_build_working_directory.sql\",\"previous_filename\":\"$DIR/V9__add_build_working_directory.sql\",\"status\":\"renamed\"}]"
expect fail "removed migration" "[{\"filename\":\"$DIR/V5__add_feedback.sql\",\"status\":\"removed\"}]"
expect fail "second page of a paginated list" "[{\"filename\":\"README.md\",\"status\":\"modified\"}][{\"filename\":\"$DIR/V1__schema.sql\",\"status\":\"modified\"}]"
expect fail "migration in a subdirectory" "[{\"filename\":\"$DIR/sub/V1__x.sql\",\"status\":\"removed\"}]"
expect fail "copied over a migration" "[{\"filename\":\"$DIR/V2__copy.sql\",\"previous_filename\":\"$DIR/V1__schema.sql\",\"status\":\"copied\"}]"
expect fail "list shorter than changed_files" "[{\"filename\":\"README.md\",\"status\":\"modified\"}]" 3001
expect fail "empty input" ""
expect fail "not a file list" "{\"message\":\"Not Found\"}"
expect pass "added migration" "[{\"filename\":\"$DIR/V14__x.sql\",\"status\":\"added\"}]"
expect pass "list matching changed_files" "[{\"filename\":\"README.md\",\"status\":\"modified\"}]" 1
expect pass "unrelated files" "[{\"filename\":\"README.md\",\"status\":\"modified\"},{\"filename\":\"$DIR/README.md\",\"status\":\"modified\"},{\"filename\":\"$DIR/R__view.sql\",\"status\":\"modified\"}]"
expect pass "empty file list" "[]"

[ "$failures" -eq 0 ]
