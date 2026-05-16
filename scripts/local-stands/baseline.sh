#!/bin/bash
# Run baseline (main branch) on localhost:${BASELINE_PORT:-4567} from a fat JAR.
#
# Why JAR (not bootRun): build once, restart many; no Gradle daemon competing
# with the candidate's bootRun for the same JVM/disk-cache resources.
#
# Required env:
#   LOCAL_VCS_ROOT         path to local clone of the registry-DSL repo
#   SERVICE_CONFIG_DIR     path to a local clone of the service-config repo
#                          (must contain application.yml and
#                          components-registry-service.yml). Spring Cloud Config
#                          is disabled; these yamls are mounted via
#                          --spring.config.additional-location.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# scripts/local-stands -> scripts -> <candidate-worktree> -> _wt -> CRS_ROOT.
CRS_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
BASELINE_WORKTREE="${BASELINE_WORKTREE:-$CRS_ROOT/_wt/local-baseline}"
LOCAL_VCS_ROOT="${LOCAL_VCS_ROOT:-}"
SERVICE_CONFIG_DIR="${SERVICE_CONFIG_DIR:-}"
BASELINE_PORT="${BASELINE_PORT:-4567}"

[ -d "$BASELINE_WORKTREE" ] || {
  echo "ERROR: baseline worktree not found at $BASELINE_WORKTREE"
  echo "  Create with: git worktree add $BASELINE_WORKTREE origin/main"
  exit 1
}
[ -n "$LOCAL_VCS_ROOT" ] || {
  echo "ERROR: LOCAL_VCS_ROOT env var is not set."
  echo "  Point it at your local clone of the registry-DSL repo, e.g."
  echo "    export LOCAL_VCS_ROOT=\"\$HOME/path/to/your/registry-dsl-clone\""
  exit 1
}
[ -d "$LOCAL_VCS_ROOT" ] || { echo "ERROR: LOCAL_VCS_ROOT=$LOCAL_VCS_ROOT is not a directory."; exit 1; }
[ -n "$SERVICE_CONFIG_DIR" ] || {
  echo "ERROR: SERVICE_CONFIG_DIR env var is not set."
  echo "  Point it at your local clone of the service-config repo, e.g."
  echo "    export SERVICE_CONFIG_DIR=\"\$HOME/path/to/your/service-config-clone\""
  exit 1
}
[ -d "$SERVICE_CONFIG_DIR" ] || { echo "ERROR: SERVICE_CONFIG_DIR=$SERVICE_CONFIG_DIR is not a directory."; exit 1; }
[ -f "$SERVICE_CONFIG_DIR/application.yml" ] || {
  echo "ERROR: $SERVICE_CONFIG_DIR/application.yml not found (does not look like a service-config dir)."
  exit 1
}
[ -f "$SERVICE_CONFIG_DIR/components-registry-service.yml" ] || {
  echo "ERROR: $SERVICE_CONFIG_DIR/components-registry-service.yml not found."
  exit 1
}

LIBS_DIR="$BASELINE_WORKTREE/components-registry-service-server/build/libs"
# bootJar produces components-registry-service-server-<version>.jar;
# the regular jar task adds an "-it-" infix and is non-runnable.
find_boot_jar() {
  ls -t "$LIBS_DIR"/components-registry-service-server-[0-9]*.jar 2>/dev/null | head -n 1
}

JAR="$(find_boot_jar || true)"

# Stale-JAR guard: if any source file changed since the JAR was built, rebuild.
# Catches the common "git pulled origin/main, forgot to rebuild" case where the
# script would otherwise report the new commit hash but run yesterday's bytecode.
SRC_ROOT="$BASELINE_WORKTREE/components-registry-service-server/src"
needs_build() {
  [ -z "$JAR" ] && return 0
  [ ! -f "$JAR" ] && return 0
  [ ! -d "$SRC_ROOT" ] && return 1
  local newer
  newer=$(find "$SRC_ROOT" "$BASELINE_WORKTREE"/*.gradle "$BASELINE_WORKTREE"/*/build.gradle \
            -newer "$JAR" \( -name '*.kt' -o -name '*.java' -o -name '*.gradle' \) \
            -print -quit 2>/dev/null || true)
  [ -n "$newer" ]
}

if needs_build; then
  if [ -z "$JAR" ]; then
    echo ">>> baseline JAR not found in $LIBS_DIR — building (one-time, ~2-3 min)..."
  else
    echo ">>> source newer than $(basename "$JAR") — rebuilding..."
  fi
  cd "$BASELINE_WORKTREE"
  ./gradlew :components-registry-service-server:bootJar --no-daemon --console=plain -x test
  JAR="$(find_boot_jar)"
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "ERROR: failed to locate or build baseline JAR under $LIBS_DIR"; exit 1; }

WORK_DIR="${WORK_DIR:-/tmp/crs-baseline-work}"
echo ">>> baseline: $BASELINE_WORKTREE @ $(git -C "$BASELINE_WORKTREE" rev-parse --short HEAD)"
echo ">>> JAR:    $JAR"
echo ">>> port:   $BASELINE_PORT"
echo ">>> config: $SERVICE_CONFIG_DIR (cloud-config disabled)"
echo ">>> VCS:    $LOCAL_VCS_ROOT  (work-dir: $WORK_DIR)"

# additional-location is searched left-to-right; later entries win for overlapping keys.
# Order: profile yamls from main worktree's dev/ dir, then service-config defaults,
# then prod overlay equivalents. The bundled application.yml in the JAR remains the
# lowest-priority fallback.
ADDITIONAL_LOCATION="file:$BASELINE_WORKTREE/components-registry-service-server/dev/"
ADDITIONAL_LOCATION="$ADDITIONAL_LOCATION,file:$SERVICE_CONFIG_DIR/application.yml"
ADDITIONAL_LOCATION="$ADDITIONAL_LOCATION,file:$SERVICE_CONFIG_DIR/components-registry-service.yml"

exec java -jar "$JAR" \
  --server.port="$BASELINE_PORT" \
  --spring.cloud.config.enabled=false \
  --spring.cloud.bootstrap.enabled=false \
  --spring.config.additional-location="$ADDITIONAL_LOCATION" \
  --spring.profiles.active=dev,dev-vcs-local,local \
  --components-registry.vcs.root="file://$LOCAL_VCS_ROOT" \
  --components-registry.work-dir="$WORK_DIR" \
  --components-registry.groovy-path="$WORK_DIR/src/main/resources" \
  --auth-server.disabled=true
