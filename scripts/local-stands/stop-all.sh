#!/bin/bash
# Tear down both bootRun stands and the local Postgres container.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
COMPOSE_FILE="$CANDIDATE_WORKTREE/docker-compose.local-postgres.yml"

BASELINE_PORT="${BASELINE_PORT:-4567}"
CANDIDATE_PORT="${CANDIDATE_PORT:-4568}"

# Port-scoped kill so parallel agent worktrees on different ports survive.
kill_port() {
  command -v lsof >/dev/null 2>&1 || { echo "WARN: lsof missing; skipping port-$1 kill"; return 0; }
  local pids
  pids=$(lsof -ti -sTCP:LISTEN tcp:"$1" 2>/dev/null || true)
  [ -z "$pids" ] && return 0
  echo "    :$1 — TERM $pids"
  kill $pids 2>/dev/null || true
  for i in $(seq 1 10); do
    pids=$(lsof -ti -sTCP:LISTEN tcp:"$1" 2>/dev/null || true)
    [ -z "$pids" ] && return 0
    sleep 1
  done
  echo "    :$1 — KILL $pids"
  kill -9 $pids 2>/dev/null || true
}

echo ">>> stopping CRS JVMs by port"
kill_port "$BASELINE_PORT"
kill_port "$CANDIDATE_PORT"

if [ -f "$COMPOSE_FILE" ]; then
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    COMPOSE=()
  fi
  if [ "${#COMPOSE[@]}" -gt 0 ]; then
    echo ">>> ${COMPOSE[*]} down ($COMPOSE_FILE)"
    "${COMPOSE[@]}" -f "$COMPOSE_FILE" down || true
  else
    echo "WARN: docker compose / docker-compose not available; skipping postgres teardown."
  fi
fi

echo ">>> done"
