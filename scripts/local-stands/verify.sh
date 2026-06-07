#!/bin/bash
# Verify gate for local stands.
#
# Flow per flags:
#   verify.sh                              — just compat against the currently-running stands
#   verify.sh --restart                    — kill candidate JVM, run candidate.sh in background,
#                                            wait for health, then compat. DSL→DB automigrate
#                                            runs via dev-db-automigrate profile on every
#                                            candidate start.
#   verify.sh --reset-db                   — implies --restart, plus tears down + recreates
#                                            the postgres volume so Flyway re-applies
#                                            V1__schema.sql. Needed when V1__schema.sql itself
#                                            changed (e.g. PR-D+E).
#   verify.sh --allow-partial-migration    — after restart, the gate parses the candidate log
#                                            for the auto-migrate summary; if any components
#                                            failed to import, it exits 4 (POLLUTED RUN). With
#                                            this flag the gate prints the same warning but
#                                            proceeds — use it only for targeted smoke that
#                                            knowingly excludes the failed components.
#                                            Has no effect without --restart/--reset-db: the
#                                            guard only fires after the gate itself spawned the
#                                            candidate (and therefore knows which log file holds
#                                            the migration summary). If a candidate that was
#                                            started polluted is still running, re-run with
#                                            --restart to surface the issue.
#
# Exit codes:
#   0  — compat run finished cleanly, gradle exit 0
#   2  — baseline not running, or env-vars missing for --restart
#   3  — candidate failed to come up after --restart
#   4  — POLLUTED RUN: auto-migrate reported failures; compat result is unreliable
#   *  — gradle exit code from compat.sh otherwise
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
ALLOW_PARTIAL=0
PASS_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --restart)                  RESTART=1 ;;
    --reset-db)                 RESTART=1; RESET_DB=1 ;;
    --allow-partial-migration)  ALLOW_PARTIAL=1 ;;
    *)                          PASS_ARGS+=("$arg") ;;
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
  pids=$(lsof -t -i tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)
  [ -z "$pids" ] && return 0
  echo "    sending TERM to PID(s) on :$port — $pids"
  kill $pids 2>/dev/null || true
  for i in $(seq 1 10); do
    pids=$(lsof -t -i tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)
    [ -z "$pids" ] && return 0
    sleep 1
  done
  echo "    still alive — SIGKILL"
  kill -9 $pids 2>/dev/null || true
}

# Parse the candidate log for the auto-migrate summary. If any component failed
# to import, the candidate is serving a partially-migrated DB — any compat diff
# count will be dominated by NULL_VS_EMPTY and STATUS_CODE 200→500 on the missing
# components, not by real backward-compat regressions. Fail-fast (exit 4) unless
# the caller passed --allow-partial-migration (targeted smoke that knowingly
# excludes the failed set).
check_migration_health() {
  local log="$1"
  [ -f "$log" ] || return 0
  local line total migrated failed skipped
  # Prefer the inner structured ImportServiceImpl marker
  #   "Migration complete: total=X, migrated=Y, failed=Z, skipped=W"
  # and fall back to the outer ComponentsRegistryServiceImpl wrapper
  #   "Auto-migrate complete: X migrated, Y skipped, Z failed".
  line=$(grep -E 'Migration complete.*total=[0-9]+' "$log" 2>/dev/null | tail -n 1 || true)
  if [ -n "$line" ]; then
    total=$(echo    "$line" | sed -E 's/.*total=([0-9]+).*/\1/')
    migrated=$(echo "$line" | sed -E 's/.*migrated=([0-9]+).*/\1/')
    failed=$(echo   "$line" | sed -E 's/.*failed=([0-9]+).*/\1/')
    skipped=$(echo  "$line" | sed -E 's/.*skipped=([0-9]+).*/\1/')
  else
    line=$(grep -E 'Auto-migrate complete:' "$log" 2>/dev/null | tail -n 1 || true)
    if [ -z "$line" ]; then
      echo ">>> migration summary: not found in $log (candidate may not run auto-migrate; skipping check)"
      return 0
    fi
    # Anchor each capture against its full surrounding template — the obvious
    # `.*([0-9]+) keyword.*` form is greedy-left and silently loses the last
    # digit of any multi-digit number ending in 0 (e.g. "10 failed" → "0").
    local pat='Auto-migrate complete: ([0-9]+) migrated, ([0-9]+) skipped, ([0-9]+) failed'
    migrated=$(echo "$line" | sed -E "s/.*$pat.*/\1/")
    skipped=$(echo  "$line" | sed -E "s/.*$pat.*/\2/")
    failed=$(echo   "$line" | sed -E "s/.*$pat.*/\3/")
    total=$((migrated + skipped + failed))
  fi

  echo ">>> migration summary: total=$total migrated=$migrated failed=$failed skipped=$skipped"

  if [ "$failed" -gt 0 ]; then
    echo
    echo "  ⚠️  POLLUTED RUN: $failed of $total components failed to import."
    echo "      Compat diffs will be dominated by NULL_VS_EMPTY on the missing"
    echo "      components and STATUS_CODE 200→500 on endpoints referencing them —"
    echo "      NOT real backward-compat regressions of the current branch."
    echo
    echo "      Failed components (from $log):"
    grep -E "Failed to migrate component '" "$log" 2>/dev/null \
      | sed -E "s/.*Failed to migrate component '([^']+)'.*/        - \1/" \
      | sort -u \
      || echo "        (no per-component error lines — check $log directly)"
    echo
    if [ "$ALLOW_PARTIAL" -eq 1 ]; then
      echo "      --allow-partial-migration set; proceeding despite the warning."
      echo "      Make sure your test filter / smoke list excludes the failed components,"
      echo "      otherwise the polluted state will leak into your diff classification."
      echo
    else
      echo "      Resolve the upstream import regression first, then re-run."
      echo "      To force-proceed with a targeted smoke that excludes the failures:"
      echo "          ./scripts/local-stands/verify.sh --restart --allow-partial-migration ..."
      exit 4
    fi
  fi
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
      lsof -i tcp:"$port" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    fi
    sleep 1
  done
  return 1
}

# === ALL HELPER FUNCTIONS MUST BE DEFINED ABOVE THIS LINE ===
#
# Anything below runs only when verify.sh is executed directly. When sourced
# (by scripts/local-stands/test/test-verify-lib.sh for self-tests), this guard
# short-circuits before the orchestration block fires, so a new helper added
# after this marker would be silently invisible to the tests.
if [ "${BASH_SOURCE[0]}" != "${0}" ]; then
  return 0
fi

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
  if ! wait_port_free "$CANDIDATE_PORT" 15; then
    echo "ERROR: :$CANDIDATE_PORT still bound after kill — refusing to spawn a new candidate"
    echo "       that would race with the surviving JVM (PR #216 shows this race leaves the"
    echo "       DB in a half-migrated state and produces garbage compat diffs)."
    echo "       Manually inspect with: lsof -i tcp:$CANDIDATE_PORT -sTCP:LISTEN"
    exit 3
  fi
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
  # Polluted-run guard. Only meaningful right after a restart since we know
  # which log file the migration just wrote to; for the no-flag path the log
  # may be missing or stale.
  check_migration_health "$CANDIDATE_LOG"
elif ! health "$CANDIDATE_PORT"; then
  echo "ERROR: candidate (:$CANDIDATE_PORT) is not running and --restart was not passed."
  echo "       Either: re-run with --restart, or start manually with ./scripts/local-stands/candidate.sh"
  exit 2
fi

echo ">>> running compat"
# `set -u` rejects bare "${PASS_ARGS[@]}" when the array is empty; use the
# `${var+...}` empty-safe expansion so the array forwards verbatim when
# present and expands to nothing when absent. (Regression-introduced by the
# polluted-run guard merge — restored.)
exec "$SCRIPT_DIR/compat.sh" ${PASS_ARGS[@]+"${PASS_ARGS[@]}"}
