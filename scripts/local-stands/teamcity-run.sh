#!/bin/bash
# End-to-end TeamCity wrapper for the local-stand compat run.
#
# Boots TWO pre-built fat JARs side-by-side on the agent: baseline (released
# old version) and candidate (current build chain output), runs the compat-test
# module from the current source checkout against both. Single TC build step
# replaces what would otherwise be five sequential script invocations.
#
# Required env (TC parameters / artifact-dependencies):
#   BASELINE_JAR              absolute path to the baseline (old) fat JAR. Wire
#                             via TC artifact-dependency on the released artifact
#                             of version `$COMPONENTS_REGISTRY_SERVICE_VERSION`.
#   CANDIDATE_JAR             absolute path to the candidate (new) fat JAR. Wire
#                             via TC artifact-dependency on the upstream build
#                             chain's output for `$BUILD_VERSION`.
#   LOCAL_VCS_ROOT            absolute path to the DSL git clone (TC VCS root
#                             checkout).
#   SERVICE_CONFIG_DIR        absolute path to the service-config clone (TC VCS
#                             root checkout). Must contain `application.yml` and
#                             `components-registry-service.yml`.
#   COMPAT_SMOKE_COMPONENTS   comma-separated real component names (TC secret
#                             parameter — never echo into committed files).
#
# Optional:
#   COMPAT_RMS_URL            release-management URL for real version sampling
#                             (TC secret parameter; absent ⇒ versioned endpoints
#                             fall back to OOR probes only).
#   COMPAT_FULL               `true` for full sweep (default `false`).
#   COMPAT_PARALLELISM        concurrent in-flight requests per stand (default 8).
#   BASELINE_PORT             default 4567.
#   CANDIDATE_PORT            default 4568.
#   BASELINE_LOG              default /tmp/crs-baseline-tc.log.
#   CANDIDATE_LOG             default /tmp/crs-candidate-tc.log.
#   BASELINE_HEALTH_TIMEOUT_ITERS  health-poll iterations × 4s for baseline.
#                             Default 75 (= 5 min). Increase on cold-cache agents.
#   CANDIDATE_HEALTH_TIMEOUT_ITERS  health-poll iterations × 4s for candidate.
#                             Default 120 (= 8 min). Includes auto-migrate.
#   RESET_DB                  `1` (default) ⇒ `docker compose down -v` BEFORE
#                             `postgres-up.sh` so the agent starts with a clean
#                             volume. Set to `0` to keep an existing DB (e.g.
#                             when iterating locally).
#   COMPONENTS_REGISTRY_SERVICE_VERSION  echoed in the header for traceability.
#   BUILD_VERSION             echoed in the header for traceability.
#
# Extra args are forwarded to `compat.sh` / gradle verbatim
# (e.g. `--tests "*ComponentDetailV2*"`).
#
# Exit codes:
#   0  — compat clean: 0 active divergences.
#   2  — env validation failed (missing var, file not found, ...).
#   3  — baseline or candidate failed to come up within the health timeout.
#   4  — POLLUTED RUN: auto-migrate on candidate reported failures
#        (count > 0); compat result is unreliable.
#   *  — gradle exit code from compat.sh otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ---------- env validation ----------
: "${BASELINE_JAR:?BASELINE_JAR must be set (path to baseline fat JAR — wire via TC artifact-dependency on COMPONENTS_REGISTRY_SERVICE_VERSION)}"
: "${CANDIDATE_JAR:?CANDIDATE_JAR must be set (path to candidate fat JAR — wire via TC artifact-dependency on BUILD_VERSION)}"
: "${LOCAL_VCS_ROOT:?LOCAL_VCS_ROOT must be set (path to DSL git clone — TC VCS root)}"
: "${SERVICE_CONFIG_DIR:?SERVICE_CONFIG_DIR must be set (path to service-config clone — TC VCS root)}"
: "${COMPAT_SMOKE_COMPONENTS:?COMPAT_SMOKE_COMPONENTS must be set (CSV of real component names — TC secret parameter)}"

[ -f "$BASELINE_JAR" ]  || { echo "ERROR: BASELINE_JAR=$BASELINE_JAR is not a file";   exit 2; }
[ -f "$CANDIDATE_JAR" ] || { echo "ERROR: CANDIDATE_JAR=$CANDIDATE_JAR is not a file"; exit 2; }

# Resolve `java` once. Both JARs are Spring Boot 3.x → require Java 17+
# (JarLauncher class-file v61). The TC agent's default `java` on $PATH
# is often Java 8, but TC exports a usable JDK as $JAVA_HOME (DSL sets
# env.JAVA_HOME = %env.JDK_21_0_x64%). Prefer $JAVA_HOME/bin/java and
# fail fast if neither path resolves to a Java 17+ binary.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi
[ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ] || {
  echo "ERROR: no usable java binary on this agent (JAVA_HOME=${JAVA_HOME:-unset}, PATH does not contain java)"
  exit 2
}
java_major=$("$JAVA_BIN" -version 2>&1 | awk -F\" '/version/ {print $2}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }')
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
  echo "ERROR: $JAVA_BIN is Java $java_major; baseline + candidate JARs need Java 17+"
  "$JAVA_BIN" -version 2>&1 || true
  exit 2
fi
echo ">>> using java: $JAVA_BIN  (major=$java_major)"
[ -d "$LOCAL_VCS_ROOT" ] || { echo "ERROR: LOCAL_VCS_ROOT=$LOCAL_VCS_ROOT is not a directory"; exit 2; }
[ -d "$SERVICE_CONFIG_DIR" ] || { echo "ERROR: SERVICE_CONFIG_DIR=$SERVICE_CONFIG_DIR is not a directory"; exit 2; }
[ -f "$SERVICE_CONFIG_DIR/application.yml" ] || {
  echo "ERROR: $SERVICE_CONFIG_DIR/application.yml not found (does not look like a service-config dir)"
  exit 2
}
[ -f "$SERVICE_CONFIG_DIR/components-registry-service.yml" ] || {
  echo "ERROR: $SERVICE_CONFIG_DIR/components-registry-service.yml not found"
  exit 2
}

BASELINE_PORT="${BASELINE_PORT:-4567}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"
BASELINE_LOG="${BASELINE_LOG:-/tmp/crs-baseline-tc.log}"
CANDIDATE_LOG="${CANDIDATE_LOG:-/tmp/crs-candidate-tc.log}"

cleanup() {
  echo ">>> teardown"
  "$SCRIPT_DIR/stop-all.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Helper: print short git revision + describe for a checkout dir. Both stands
# share the SAME LOCAL_VCS_ROOT / SERVICE_CONFIG_DIR / trace-data — recording
# the revision in the banner makes "same CR version" guarantee auditable
# without grepping for `Start computing revisions` in the TC log.
git_id() {
    local dir="$1"
    if [ -d "$dir/.git" ] || git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        local rev describe
        rev=$(git -C "$dir" rev-parse --short=12 HEAD 2>/dev/null || echo "<unknown>")
        describe=$(git -C "$dir" describe --tags --always --dirty 2>/dev/null || echo "")
        printf "%s (%s)" "$rev" "${describe:-no-describe}"
    else
        printf "<not a git checkout>"
    fi
}

LOCAL_VCS_ROOT_REV=$(git_id "$LOCAL_VCS_ROOT")
SERVICE_CONFIG_REV=$(git_id "$SERVICE_CONFIG_DIR")
TRACE_DATA_DIR="${TRACE_DATA_DIR:-${PWD}/trace-data}"
TRACE_DATA_REV=$(git_id "$TRACE_DATA_DIR")

echo "============================================================"
echo "TeamCity compat run"
echo "  baseline version:  ${COMPONENTS_REGISTRY_SERVICE_VERSION:-<unset>}"
echo "  baseline JAR:      $BASELINE_JAR"
echo "  candidate version: ${BUILD_VERSION:-<unset>}"
echo "  candidate JAR:     $CANDIDATE_JAR"
echo "  DSL root:          $LOCAL_VCS_ROOT"
echo "  DSL revision:      $LOCAL_VCS_ROOT_REV   ← shared by baseline AND candidate"
echo "  service-config:    $SERVICE_CONFIG_DIR"
echo "  service-cfg rev:   $SERVICE_CONFIG_REV"
echo "  trace-data:        $TRACE_DATA_DIR"
echo "  trace-data rev:    $TRACE_DATA_REV"
echo "  ports:             baseline=$BASELINE_PORT  candidate=$CANDIDATE_PORT"
echo "  compat.full:       ${COMPAT_FULL:-false}    parallelism=${COMPAT_PARALLELISM:-8}"
echo "============================================================"

# ---------- Stage 1/4: postgres ----------
echo ">>> Stage 1/4: postgres"
RESET_DB="${RESET_DB:-1}"
if [ "$RESET_DB" = "1" ]; then
  # State contamination guard (Stage-2 review BLOCKING): postgres-up.sh runs
  # `docker compose up -d` against a NAMED VOLUME, and stop-all.sh tears down
  # WITHOUT `-v`. On a persistent agent the DB survives between TC runs;
  # idempotent migrate then skips already-imported components and leaves
  # ghost rows from runs whose DSL had components since removed. Wipe first.
  COMPOSE_FILE="$CANDIDATE_WORKTREE/docker-compose.local-postgres.yml"
  if [ -f "$COMPOSE_FILE" ]; then
    if docker compose version >/dev/null 2>&1; then
      docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
    elif command -v docker-compose >/dev/null 2>&1; then
      docker-compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
    fi
  fi
fi
"$SCRIPT_DIR/postgres-up.sh"

# ---------- Stage 2/4: baseline JAR ----------
echo ">>> Stage 2/4: baseline JAR (V1 mode)"
BASELINE_WORK_DIR="${BASELINE_WORK_DIR:-/tmp/crs-baseline-tc-work}"
# Spring `additional-location` is searched left-to-right; later wins for
# overlapping keys. Order mirrors baseline.sh: dev/ profile yamls first,
# then service-config defaults. The JAR's bundled application.yml is the
# lowest-priority fallback.
BASELINE_ADDITIONAL="file:$CANDIDATE_WORKTREE/components-registry-service-server/dev/"
BASELINE_ADDITIONAL="$BASELINE_ADDITIONAL,file:$SERVICE_CONFIG_DIR/application.yml"
BASELINE_ADDITIONAL="$BASELINE_ADDITIONAL,file:$SERVICE_CONFIG_DIR/components-registry-service.yml"

nohup "$JAVA_BIN" -jar "$BASELINE_JAR" \
  --server.port="$BASELINE_PORT" \
  --spring.cloud.config.enabled=false \
  --spring.cloud.bootstrap.enabled=false \
  --spring.config.additional-location="$BASELINE_ADDITIONAL" \
  --spring.profiles.active=dev,dev-vcs-local,local \
  --components-registry.vcs.root="file://$LOCAL_VCS_ROOT" \
  --components-registry.work-dir="$BASELINE_WORK_DIR" \
  --components-registry.groovy-path="$BASELINE_WORK_DIR/src/main/resources" \
  --auth-server.disabled=true \
  >"$BASELINE_LOG" 2>&1 &
BASELINE_PID=$!
echo "    baseline started (PID=$BASELINE_PID, log=$BASELINE_LOG)"

BASELINE_HEALTH_TIMEOUT_ITERS="${BASELINE_HEALTH_TIMEOUT_ITERS:-75}"
echo -n "    waiting for baseline health on :$BASELINE_PORT (up to $((BASELINE_HEALTH_TIMEOUT_ITERS * 4 / 60)) min)"
for i in $(seq 1 "$BASELINE_HEALTH_TIMEOUT_ITERS"); do
  if curl -fsS --max-time 2 "http://localhost:$BASELINE_PORT/actuator/health" >/dev/null 2>&1; then
    echo
    echo "    baseline up after $((i * 4))s"
    break
  fi
  if [ $((i % 15)) -eq 0 ]; then echo -n " ${i}/$BASELINE_HEALTH_TIMEOUT_ITERS "; else echo -n "."; fi
  sleep 4
done
echo
if ! curl -fsS --max-time 2 "http://localhost:$BASELINE_PORT/actuator/health" >/dev/null 2>&1; then
  echo "ERROR: baseline failed to come up — tail of $BASELINE_LOG:"
  tail -n 40 "$BASELINE_LOG" || true
  exit 3
fi

# ---------- Stage 3/4: candidate JAR ----------
echo ">>> Stage 3/4: candidate JAR (schema-v2 DB-mode + auto-migrate)"
CANDIDATE_WORK_DIR="${CANDIDATE_WORK_DIR:-/tmp/crs-candidate-tc-work}"
# Candidate's profile suite mirrors candidate.sh --mode=db:
#   dev                  — base dev overlay
#   dev-vcs-local        — file:// VCS access (no remote bitbucket pull)
#   dev-db-automigrate   — run DSL→DB migration at startup
#   dev-db-only          — components-registry.default-source=db (schema-v2 code
#                          path active for unmigrated-component fallback)
#   local                — local stand identity marker
CANDIDATE_ADDITIONAL="file:$CANDIDATE_WORKTREE/components-registry-service-server/dev/"
CANDIDATE_ADDITIONAL="$CANDIDATE_ADDITIONAL,file:$SERVICE_CONFIG_DIR/components-registry-service.yml"
CANDIDATE_PROFILES="dev,dev-vcs-local,dev-db-automigrate,dev-db-only,local"

nohup "$JAVA_BIN" -jar "$CANDIDATE_JAR" \
  --server.port="$CANDIDATE_PORT" \
  --spring.profiles.active="$CANDIDATE_PROFILES" \
  --spring.config.additional-location="$CANDIDATE_ADDITIONAL" \
  --components-registry.vcs.root="file://$LOCAL_VCS_ROOT" \
  --components-registry.work-dir="$CANDIDATE_WORK_DIR" \
  --components-registry.groovy-path="$CANDIDATE_WORK_DIR/src/main/resources" \
  --auth-server.disabled=true \
  >"$CANDIDATE_LOG" 2>&1 &
CANDIDATE_PID=$!
echo "    candidate started (PID=$CANDIDATE_PID, log=$CANDIDATE_LOG)"

CANDIDATE_HEALTH_TIMEOUT_ITERS="${CANDIDATE_HEALTH_TIMEOUT_ITERS:-120}"
# Auto-migrate import takes ~30-60s, plus startup ~20s, plus headroom for slow agents.
echo -n "    waiting for candidate health on :$CANDIDATE_PORT (up to $((CANDIDATE_HEALTH_TIMEOUT_ITERS * 4 / 60)) min — includes auto-migrate)"
for i in $(seq 1 "$CANDIDATE_HEALTH_TIMEOUT_ITERS"); do
  if curl -fsS --max-time 2 "http://localhost:$CANDIDATE_PORT/actuator/health" >/dev/null 2>&1; then
    echo
    echo "    candidate up after $((i * 4))s"
    break
  fi
  if [ $((i % 15)) -eq 0 ]; then echo -n " ${i}/$CANDIDATE_HEALTH_TIMEOUT_ITERS "; else echo -n "."; fi
  sleep 4
done
echo
if ! curl -fsS --max-time 2 "http://localhost:$CANDIDATE_PORT/actuator/health" >/dev/null 2>&1; then
  echo "ERROR: candidate failed to come up — tail of $CANDIDATE_LOG:"
  tail -n 60 "$CANDIDATE_LOG" || true
  exit 3
fi

# Polluted-migration guard: reuse verify.sh's parser. verify.sh's source-guard
# (added by TD-007 L2 / PR #229) returns 0 before its orchestration block when
# sourced, exposing the helpers without side effects.
# shellcheck disable=SC1090
source "$SCRIPT_DIR/verify.sh"
# `check_migration_health` exits 4 on its own (failed > 0 and ALLOW_PARTIAL=0).
ALLOW_PARTIAL="${ALLOW_PARTIAL:-0}"
check_migration_health "$CANDIDATE_LOG"

# Hard assert candidate routing mode (Stage-2 review NIT): if `dev-db-only.yml`
# is ever dropped and the JVM silently falls back to `default-source=git`,
# the compat run would tolerate it via the env-warning record but exit 0
# anyway when no other diffs surface. Fail-fast here so a misconfiguration
# is impossible to miss.
CANDIDATE_STATUS_URL="http://localhost:$CANDIDATE_PORT/rest/api/2/components-registry/service/status"
CANDIDATE_DEFAULT_SOURCE=$(curl -fsS --max-time 5 "$CANDIDATE_STATUS_URL" | sed -nE 's/.*"defaultSource":"([^"]*)".*/\1/p' || true)
if [ -z "$CANDIDATE_DEFAULT_SOURCE" ]; then
  echo "WARN: could not read candidate /service/status.defaultSource (older candidate JVM that does not expose the field?)"
  echo "      Proceeding — compat-test's L3 preflight (SnapshotPreconditionTest) will catch a mismatch."
elif [ "$CANDIDATE_DEFAULT_SOURCE" != "db" ]; then
  echo "ERROR: candidate reports defaultSource=$CANDIDATE_DEFAULT_SOURCE — expected 'db'."
  echo "       The schema-v2 DB resolver is dormant; compat would measure V1-vs-V1, not schema-v2-vs-V1."
  echo "       Check that the 'dev-db-only' profile is active and that application-dev-db-only.yml is on the classpath."
  exit 2
else
  echo "    candidate routing: defaultSource=db ✓"
fi

# ---------- Stage 4/4: compat ----------
echo ">>> Stage 4/4: compat-test"
# compat.sh exports COMPAT_BASELINE_URL=http://localhost:$BASELINE_PORT and
# COMPAT_CANDIDATE_URL=http://localhost:$CANDIDATE_PORT, then invokes the
# :components-registry-compat-test:test gradle task. It already accepts
# COMPAT_RMS_URL / COMPAT_FULL / COMPAT_PARALLELISM / COMPAT_SMOKE_COMPONENTS
# from the parent shell.
#
# Run as a foreground child (NOT `exec`) so the EXIT trap fires when compat
# finishes — Stage-2 review found that `exec` was replacing the shell process
# and silently discarding the trap, leaking the baseline/candidate JVMs and
# the postgres container between TC runs on a persistent agent.
"$SCRIPT_DIR/compat.sh" "$@"
COMPAT_EXIT=$?
exit $COMPAT_EXIT
