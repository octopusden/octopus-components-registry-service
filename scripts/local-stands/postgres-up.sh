#!/bin/bash
# Start local Postgres for the candidate stand (schema-v2 DB-mode).
# Uses docker-compose.local-postgres.yml from the candidate worktree.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CANDIDATE_WORKTREE="${CANDIDATE_WORKTREE:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
COMPOSE_FILE="$CANDIDATE_WORKTREE/docker-compose.local-postgres.yml"

[ -f "$COMPOSE_FILE" ] || { echo "ERROR: $COMPOSE_FILE not found"; exit 1; }

# Some hosts have only the v1 hyphenated `docker-compose`; others have the v2
# `docker compose` subcommand. Detect once.
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "ERROR: neither 'docker compose' nor 'docker-compose' is available on this host."
  exit 1
fi

echo ">>> ${COMPOSE[*]} up -d  ($COMPOSE_FILE)"
"${COMPOSE[@]}" -f "$COMPOSE_FILE" up -d

echo ">>> waiting for Postgres healthcheck..."
for i in $(seq 1 30); do
  health=$("${COMPOSE[@]}" -f "$COMPOSE_FILE" ps --format json postgres 2>/dev/null | head -1 | python3 -c 'import sys,json; print(json.loads(sys.stdin.read() or "{}").get("Health","unknown"))' 2>/dev/null || echo unknown)
  if [ "$health" = "healthy" ]; then
    echo ">>> Postgres healthy (port ${CRS_DB_PORT:-5432})."
    exit 0
  fi
  sleep 2
done

echo "WARN: Postgres did not become healthy within 60 sec. Check '${COMPOSE[*]} -f $COMPOSE_FILE logs postgres'."
exit 1
