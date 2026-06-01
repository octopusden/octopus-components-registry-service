#!/bin/bash
# Run candidate (feat/schema-v2-sql) on localhost:${CANDIDATE_PORT:-4568}.
#
# Required env:
#   LOCAL_VCS_ROOT         path to local clone of the registry-DSL repo (DSL→DB source)
#   SERVICE_CONFIG_DIR     path to local clone of the service-config repo; overlaid on the
#                          candidate's dev/ profile yamls so production-only keys
#                          (components-registry.product-type.*, supportedGroupIds, ...)
#                          are present
#
# Flags:
#   --mode=db   (default) — components-registry.default-source=db; ComponentRoutingResolver
#                  picks DatabaseComponentRegistryResolver (schema-v2 code path) as the
#                  fallback. After dev-db-automigrate completes, every migrated component
#                  has a `component_sources` row with source='db', so the fallback governs
#                  unmigrated components only. This is the mode in which the four cluster-
#                  fix PRs (#208/#209/#211/#212) are actually exercised.
#   --mode=vcs           — no-db profile (issue #310): the JDBC/JPA/Flyway auto-configs
#                  are excluded so the candidate boots with NO database;
#                  components-registry.default-source=git, the V1 in-memory
#                  EscrowConfigurationLoader serves. No Postgres required. Use for the
#                  deploy-without-migration no-op check and V1-vs-V1 parity-debugging.
#
# db-mode requires Postgres running (see postgres-up.sh) and triggers a full DSL→DB
# migration at startup (~30-60 sec on first run); vcs/no-db mode needs neither.
# Unlike baseline.sh, this script does NOT explicitly disable Spring Cloud Config — the
# dev profile suite doesn't request cloud-config, so it's implicitly off.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
LOCAL_VCS_ROOT="${LOCAL_VCS_ROOT:-}"
SERVICE_CONFIG_DIR="${SERVICE_CONFIG_DIR:-}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"
MODE="db"
for arg in "$@"; do
  case "$arg" in
    --mode=db)   MODE="db" ;;
    --mode=vcs)  MODE="vcs" ;;
    --mode=*)    echo "ERROR: unknown --mode value: $arg (expected --mode=db or --mode=vcs)"; exit 1 ;;
    *)           echo "ERROR: unknown arg: $arg"; exit 1 ;;
  esac
done

[ -d "$CANDIDATE_WORKTREE" ] || { echo "ERROR: candidate worktree not found at $CANDIDATE_WORKTREE"; exit 1; }
[ -n "$LOCAL_VCS_ROOT" ] || { echo "ERROR: LOCAL_VCS_ROOT env var is not set."; echo "  Point it at your local clone of the registry-DSL repo, e.g."; echo "    export LOCAL_VCS_ROOT=\"\$HOME/path/to/your/registry-dsl-clone\""; exit 1; }
[ -d "$LOCAL_VCS_ROOT" ] || { echo "ERROR: LOCAL_VCS_ROOT=$LOCAL_VCS_ROOT is not a directory."; exit 1; }
[ -n "$SERVICE_CONFIG_DIR" ] || {
  echo "ERROR: SERVICE_CONFIG_DIR env var is not set."
  echo "  Same yaml the baseline uses; it carries production-grade values"
  echo "  (product-type, supportedGroupIds, ...) that are absent from the candidate's"
  echo "  bundled dev/ overlays. Point it at your local service-config clone, e.g."
  echo "    export SERVICE_CONFIG_DIR=\"\$HOME/path/to/your/service-config-clone\""
  exit 1
}
[ -d "$SERVICE_CONFIG_DIR" ] || { echo "ERROR: SERVICE_CONFIG_DIR=$SERVICE_CONFIG_DIR is not a directory."; exit 1; }
[ -f "$SERVICE_CONFIG_DIR/components-registry-service.yml" ] || {
  echo "ERROR: $SERVICE_CONFIG_DIR/components-registry-service.yml not found."
  exit 1
}

ADDITIONAL_LOCATION="file:$CANDIDATE_WORKTREE/components-registry-service-server/dev/"
ADDITIONAL_LOCATION="$ADDITIONAL_LOCATION,file:$SERVICE_CONFIG_DIR/components-registry-service.yml"

WORK_DIR="${WORK_DIR:-/tmp/crs-candidate-work}"
NODB_OVERRIDE_ARGS=""
if [ "$MODE" = "db" ]; then
  PROFILES="dev,dev-vcs-local,dev-db-automigrate,dev-db-only,local"
else
  # vcs (no-db) mode: the `no-db` profile excludes the JDBC/JPA/Flyway auto-configs
  # so the candidate boots with no database at all (issue #310); no Postgres needed.
  PROFILES="dev,dev-vcs-local,no-db,local"
  # Force all three no-db knobs at the CLI (highest precedence) so a stray override
  # in service-config (loaded via additional-location, which outranks the bundled
  # no-db profile YAML) cannot re-enable the DB beans / migration / db routing while
  # JPA autoconfig stays excluded — mirrors teamcity-run.sh's git-mode args exactly.
  NODB_OVERRIDE_ARGS=" --components-registry.database.enabled=false"
  NODB_OVERRIDE_ARGS="$NODB_OVERRIDE_ARGS --components-registry.auto-migrate=false"
  NODB_OVERRIDE_ARGS="$NODB_OVERRIDE_ARGS --components-registry.default-source=git"
fi
cd "$CANDIDATE_WORKTREE"
echo ">>> candidate: $CANDIDATE_WORKTREE @ $(git rev-parse --short HEAD)"
echo ">>> mode: $MODE   profiles: $PROFILES"
if [ "$MODE" = "db" ]; then
  echo ">>> port: $CANDIDATE_PORT,  VCS root: $LOCAL_VCS_ROOT  (work-dir: $WORK_DIR),  DB: localhost:${CRS_DB_PORT:-5432}"
else
  echo ">>> port: $CANDIDATE_PORT,  VCS root: $LOCAL_VCS_ROOT  (work-dir: $WORK_DIR),  DB: none (no-db mode)"
fi
echo ">>> config: $SERVICE_CONFIG_DIR (overlaid on dev/)"
exec ./gradlew :components-registry-service-server:bootRun --no-daemon --console=plain \
  --args="--server.port=$CANDIDATE_PORT \
          --spring.profiles.active=$PROFILES \
          --spring.config.additional-location=$ADDITIONAL_LOCATION \
          --components-registry.vcs.root=file://$LOCAL_VCS_ROOT \
          --components-registry.work-dir=$WORK_DIR \
          --components-registry.groovy-path=$WORK_DIR/src/main/resources \
          --auth-server.disabled=true$NODB_OVERRIDE_ARGS"
