#!/bin/bash
# Regression gate: compare the CURRENT compat run's diff set against a FROZEN
# baseline diff set (diff-of-diffs). The verdict is per diff-key:
#
#   FIXED     — present in baseline, absent in current
#   REMAINING — present in both
#   NEW       — absent in baseline, present in current  → exit 1 (reject)
#
# This is the per-commit gate of the compat burndown: a fix may only land when
# it FIXES at least its target cluster and introduces ZERO NEW diffs anywhere
# else in the sweep.
#
# Usage:
#   ./scripts/local-stands/diff-of-diffs.sh \
#     --baseline-dir /path/to/frozen-baseline-reports \
#     --current-dir  components-registry-compat-test/build/reports/compat
#
# Both dirs must contain diff-worker-*.ndjson files (the per-worker output of a
# compat run). --current-dir defaults to the module's build/reports/compat.
# The frozen baseline lives OUTSIDE the repo (production-derived data).
#
# Implementation lives in the `compatDiffOfDiffs` gradle task
# (components-registry-compat-test/build.gradle) so it shares the ndjson
# parsing, known-deltas suppression and path normalisation of the
# compatibilityReporter.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

BASELINE_DIR=""
CURRENT_DIR="$CANDIDATE_WORKTREE/components-registry-compat-test/build/reports/compat"
while [ $# -gt 0 ]; do
  case "$1" in
    --baseline-dir) BASELINE_DIR="$2"; shift 2 ;;
    --baseline-dir=*) BASELINE_DIR="${1#*=}"; shift ;;
    --current-dir) CURRENT_DIR="$2"; shift 2 ;;
    --current-dir=*) CURRENT_DIR="${1#*=}"; shift ;;
    *) echo "ERROR: unknown arg: $1 (expected --baseline-dir/--current-dir)"; exit 2 ;;
  esac
done

[ -n "$BASELINE_DIR" ] || { echo "ERROR: --baseline-dir is required (frozen oracle, outside the repo)"; exit 2; }
[ -d "$BASELINE_DIR" ] || { echo "ERROR: baseline dir not found: $BASELINE_DIR"; exit 2; }
[ -d "$CURRENT_DIR" ]  || { echo "ERROR: current dir not found: $CURRENT_DIR"; exit 2; }

cd "$CANDIDATE_WORKTREE"
exec ./gradlew :components-registry-compat-test:compatDiffOfDiffs \
  -Pcompat.dod.baseline-dir="$BASELINE_DIR" \
  -Pcompat.dod.current-dir="$CURRENT_DIR" \
  --no-daemon --console=plain
