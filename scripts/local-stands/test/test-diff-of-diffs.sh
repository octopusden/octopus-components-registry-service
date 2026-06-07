#!/usr/bin/env bash
# Self-tests for the compatDiffOfDiffs gate (components-registry-compat-test/build.gradle).
# Builds synthetic report dirs in a temp area and asserts the gate's exit code
# for the cases that are easy to get wrong:
#
#   1. baseline has a diff, current is CLEAN (no diff-worker file at all) →
#      MUST pass (exit 0) and classify the baseline diff as FIXED. Regression
#      guard: DiffCollector only writes a diff-worker file on the first diff,
#      so a fully-clean run (the burndown end state) writes NONE — the gate
#      must treat that as an empty diff set, not error. (P2, 2026-06-07.)
#   2. current dir has NO exec-worker file → MUST abort (completeness guard:
#      a truncated/again-nonexistent run cannot prove it executed, so its
#      missing diffs must not read as FIXED).
#   3. current introduces a NEW diff absent from baseline → MUST fail.
#
# Invokes the real gradle task (slow ~10-20s). No prod literals in fixtures.
#
# Usage:
#   bash scripts/local-stands/test/test-diff-of-diffs.sh
#   → exit 0 on green, non-zero on any failure.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
GRADLEW="$REPO_ROOT/gradlew"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { echo ">>> $*"; }

# Each exec-worker line is just counted (not parsed) by the completeness guard.
mk_exec() { python3 -c "open('$1','w').write('{\"e\":1}\n'*200)"; }

run_dod() {
  "$GRADLEW" -p "$REPO_ROOT" :components-registry-compat-test:compatDiffOfDiffs \
    -Pcompat.dod.baseline-dir="$1" -Pcompat.dod.current-dir="$2" \
    --no-daemon --console=plain -q
}

DIFF_LINE='{"ts":"t","endpoint":"GET /rest/api/2/components/sample/versions/1.0/distribution","pathParams":{"component":"sample","version":"1.0"},"queryParams":{},"category":"STATUS_CODE_DIFF","layer":"raw","baselineValue":"404","candidateValue":"200","entityKey":"sample @ 1.0","jsonPath":"$"}'
NEW_LINE='{"ts":"t","endpoint":"GET /rest/api/2/components/other/versions/2.0/distribution","pathParams":{"component":"other","version":"2.0"},"queryParams":{},"category":"STATUS_CODE_DIFF","layer":"raw","baselineValue":"404","candidateValue":"200","entityKey":"other @ 2.0","jsonPath":"$"}'

# --- baseline: one diff + exec log ---
mkdir -p "$WORK/baseline"
printf '%s\n' "$DIFF_LINE" > "$WORK/baseline/diff-worker-a.ndjson"
mk_exec "$WORK/baseline/exec-worker-a.ndjson"

# Case 1: current clean (exec log only, NO diff-worker) → expect pass (FIXED=1).
mkdir -p "$WORK/clean"
mk_exec "$WORK/clean/exec-worker-b.ndjson"
if run_dod "$WORK/baseline" "$WORK/clean"; then
  note "PASS case 1: clean current → gate green (baseline diff classified FIXED)"
else
  note "FAIL case 1: clean current was rejected (the P2 regression)"; fail=1
fi

# Case 2: current with no exec-worker at all → expect abort.
mkdir -p "$WORK/no-exec"
printf '%s\n' "$DIFF_LINE" > "$WORK/no-exec/diff-worker-c.ndjson"
if run_dod "$WORK/baseline" "$WORK/no-exec"; then
  note "FAIL case 2: missing exec-worker should have aborted but passed"; fail=1
else
  note "PASS case 2: missing exec-worker aborts (completeness guard)"
fi

# Case 3: current introduces a NEW diff → expect fail.
mkdir -p "$WORK/newdiff"
printf '%s\n%s\n' "$DIFF_LINE" "$NEW_LINE" > "$WORK/newdiff/diff-worker-d.ndjson"
mk_exec "$WORK/newdiff/exec-worker-d.ndjson"
if run_dod "$WORK/baseline" "$WORK/newdiff"; then
  note "FAIL case 3: a NEW diff should have failed the gate but passed"; fail=1
else
  note "PASS case 3: NEW diff fails the gate"
fi

if [ "$fail" -eq 0 ]; then
  note "ALL GREEN"
else
  note "FAILURES above"
fi
exit "$fail"
