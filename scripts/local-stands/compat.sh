#!/bin/bash
# Wait for both stands to be healthy, then run :components-registry-compat-test:test
# against them. Extra args are forwarded to gradle (e.g. `--tests "*.ComponentDetailV2*"`).
#
# Real component names are confidential — do NOT bake them into this script or any
# committed file. Pass them at runtime via the env (gradle forwards COMPAT_SMOKE_COMPONENTS
# to the test JVM as a system property):
#
#   export COMPAT_SMOKE_COMPONENTS="name1,name2,name3"
#   ./scripts/local-stands/compat.sh
#
# Or sourcing a local-only file kept outside the repo (e.g. in /tmp or $HOME):
#
#   source /tmp/compat-aug-env.sh   # exports COMPAT_SMOKE_COMPONENTS, COMPAT_RMS_URL, ...
#   ./scripts/local-stands/compat.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
BASELINE_PORT="${BASELINE_PORT:-4567}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"

wait_for() {
  local name="$1" port="$2"
  echo -n ">>> waiting for $name (localhost:$port/actuator/health)..."
  for i in $(seq 1 90); do
    if curl -fsS --max-time 2 "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
      echo " up"
      return 0
    fi
    echo -n "."
    sleep 2
  done
  echo " TIMEOUT"
  return 1
}

wait_for baseline  "$BASELINE_PORT"
wait_for candidate "$CANDIDATE_PORT"

export COMPAT_BASELINE_URL="http://localhost:$BASELINE_PORT"
export COMPAT_CANDIDATE_URL="http://localhost:$CANDIDATE_PORT"
# COMPAT_RMS_URL is optional — caller can export it for real version sampling.
echo ">>> running compat-test from $CANDIDATE_WORKTREE"
echo "    COMPAT_BASELINE_URL=$COMPAT_BASELINE_URL"
echo "    COMPAT_CANDIDATE_URL=$COMPAT_CANDIDATE_URL"
echo "    COMPAT_RMS_URL=${COMPAT_RMS_URL:-<unset; version sampling will return empty>}"

cd "$CANDIDATE_WORKTREE"
exec ./gradlew :components-registry-compat-test:test --no-daemon --console=plain "$@"
