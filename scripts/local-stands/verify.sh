#!/bin/bash
# Verify gate for local stands.
#
# Flow per flags:
#   verify.sh                        — just compat against the currently-running stands
#   verify.sh --restart              — kill candidate JVM, run candidate.sh in background,
#                                      wait for health, then compat. DSL→DB automigrate runs
#                                      via dev-db-automigrate profile on every candidate start.
#   verify.sh --reset-db             — implies --restart, plus tears down + recreates the
#                                      postgres volume so Flyway re-applies V1__schema.sql.
#                                      Needed when V1__schema.sql itself changed (e.g. PR-D+E).
#
# Extra args are forwarded verbatim to compat.sh / gradle (e.g. --tests "*BuildToolsV2*").
#
# Subagent workflow: a PR agent working in its own worktree exports CANDIDATE_WORKTREE
# to its path before invoking; candidate.sh respects that override so the rebuilt JVM
# runs the agent's code.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESTART=0
RESET_DB=0
PASS_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --restart)  RESTART=1 ;;
    --reset-db) RESTART=1; RESET_DB=1 ;;
    *) PASS_ARGS+=("$arg") ;;
  esac
done

BASELINE_PORT="${BASELINE_PORT:-4567}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"

health() { curl -fsS --max-time 2 "http://localhost:$1/actuator/health" >/dev/null 2>&1; }

# Port-scoped kill — only touches the JVM listening on the given port, so
# parallel agent worktrees on different ports (or unrelated Spring Boot apps
# on this host) are not affected. Falls back gracefully if lsof is absent.
kill_port() {
  local port="$1"
  command -v lsof >/dev/null 2>&1 || { echo "WARN: lsof not available; skipping port-$port kill"; return 0; }
  local pids
  pids=$(lsof -ti -sTCP:LISTEN tcp:"$port" 2>/dev/null || true)
  [ -z "$pids" ] && return 0
  echo "    sending TERM to PID(s) on :$port — $pids"
  kill $pids 2>/dev/null || true
  for i in $(seq 1 10); do
    pids=$(lsof -ti -sTCP:LISTEN tcp:"$port" 2>/dev/null || true)
    [ -z "$pids" ] && return 0
    sleep 1
  done
  echo "    still alive — SIGKILL"
  kill -9 $pids 2>/dev/null || true
}

# Wait until $port is free, capped at $2 seconds.
wait_port_free() {
  local port="$1" cap="${2:-15}"
  local have_lsof=0
  command -v lsof >/dev/null 2>&1 && have_lsof=1
  for i in $(seq 1 "$cap"); do
    if ! health "$port"; then
      # If lsof is missing, fall back to health-only as the readiness signal.
      [ "$have_lsof" -eq 0 ] && return 0
      lsof -i -sTCP:LISTEN tcp:"$port" >/dev/null 2>&1 || return 0
    fi
    sleep 1
  done
  return 1
}

if ! health "$BASELINE_PORT"; then
  echo "ERROR: baseline (:$BASELINE_PORT) is not running."
  echo "       Start with: ./scripts/local-stands/baseline.sh"
  exit 2
fi

# Early env validation for any flag that respawns candidate — surface the
# missing-env error here rather than 5 minutes later in /tmp/crs-candidate.log.
if [ "$RESTART" -eq 1 ]; then
  : "${LOCAL_VCS_ROOT:?ERROR: LOCAL_VCS_ROOT must be exported when using --restart/--reset-db (passed through to candidate.sh)}"
  : "${SERVICE_CONFIG_DIR:?ERROR: SERVICE_CONFIG_DIR must be exported when using --restart/--reset-db (passed through to candidate.sh)}"
fi

if [ "$RESET_DB" -eq 1 ]; then
  echo ">>> --reset-db: wiping postgres volume and recreating"
  kill_port "$CANDIDATE_PORT"
  wait_port_free "$CANDIDATE_PORT" 15 || echo "WARN: :$CANDIDATE_PORT still bound; continuing anyway"
  COMPOSE_FILE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}/docker-compose.local-postgres.yml"
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    echo "ERROR: docker compose / docker-compose not available."
    exit 2
  fi
  "${COMPOSE[@]}" -f "$COMPOSE_FILE" down -v
  "$SCRIPT_DIR/postgres-up.sh"
fi

if [ "$RESTART" -eq 1 ]; then
  echo ">>> --restart: stopping candidate JVM on :$CANDIDATE_PORT"
  kill_port "$CANDIDATE_PORT"
  wait_port_free "$CANDIDATE_PORT" 10 || echo "WARN: :$CANDIDATE_PORT still bound; continuing anyway"
  CANDIDATE_LOG="/tmp/crs-candidate-${CANDIDATE_PORT}.log"
  echo ">>> starting candidate.sh in background (logs: $CANDIDATE_LOG)"
  nohup bash "$SCRIPT_DIR/candidate.sh" >"$CANDIDATE_LOG" 2>&1 &
  echo -n ">>> waiting for candidate health on :$CANDIDATE_PORT (up to 5 min)"
  for i in $(seq 1 75); do
    if health "$CANDIDATE_PORT"; then
      echo
      echo "    candidate up after $((i * 4)) s"
      break
    fi
    # One dot every 4 s = 15/min, two-digit minute marks for orientation.
    if [ $((i % 15)) -eq 0 ]; then echo -n " ${i}/75 "; else echo -n "."; fi
    sleep 4
  done
  echo
  if ! health "$CANDIDATE_PORT"; then
    echo "ERROR: candidate failed to come up — tail of $CANDIDATE_LOG:"
    tail -n 40 "$CANDIDATE_LOG" || true
    exit 3
  fi
elif ! health "$CANDIDATE_PORT"; then
  echo "ERROR: candidate (:$CANDIDATE_PORT) is not running and --restart was not passed."
  echo "       Either: re-run with --restart, or start manually with ./scripts/local-stands/candidate.sh"
  exit 2
fi

echo ">>> running compat"
exec "$SCRIPT_DIR/compat.sh" "${PASS_ARGS[@]}"
