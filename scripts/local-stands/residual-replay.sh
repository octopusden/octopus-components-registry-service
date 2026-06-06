#!/bin/bash
# Replay only the failing tuples from a prior TC run (exec-worker ndjson).
#
# Prereqs: baseline + candidate stands up (teamcity-run.sh or verify.sh --restart).
# Generate fixture from TC artifacts:
#   python3 scripts/local-stands/extract-residual-fixture.py \
#     exec-worker-*.ndjson /tmp/residual-tc3841.txt
#
# Usage:
#   export COMPAT_RESIDUAL_FILE=/tmp/residual-tc3841.txt
#   export COMPAT_RESIDUAL_FILE=components-registry-compat-test/src/test/resources/compat/cluster-50-fixture.txt
#   ./scripts/local-stands/residual-replay.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
BASELINE_PORT="${BASELINE_PORT:-4567}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"

: "${COMPAT_RESIDUAL_FILE:?COMPAT_RESIDUAL_FILE must point at extract-residual-fixture.py output}"

export COMPAT_BASELINE_URL="${COMPAT_BASELINE_URL:-http://localhost:$BASELINE_PORT}"
export COMPAT_CANDIDATE_URL="${COMPAT_CANDIDATE_URL:-http://localhost:$CANDIDATE_PORT}"
export COMPAT_FULL="${COMPAT_FULL:-true}"
export COMPAT_PARALLELISM="${COMPAT_PARALLELISM:-8}"

cd "$CANDIDATE_WORKTREE"
exec ./gradlew :components-registry-compat-test:test \
  --tests "org.octopusden.octopus.components.registry.compat.ResidualReplayCompatTest" \
  --no-daemon --console=plain "$@"
