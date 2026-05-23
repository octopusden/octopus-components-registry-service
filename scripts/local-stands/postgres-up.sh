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

echo ">>> ${COMPOSE[*]} up -d --wait --wait-timeout 180  ($COMPOSE_FILE)"
# `--wait` makes compose itself block until every service marked with a
# healthcheck transitions to `healthy` (or errors); `--wait-timeout 180`
# caps that at 3 minutes — enough room for a cold-cache `postgres:16`
# pull + volume init on a TC podman agent, where the previous 60-sec
# busy-poll (30×2s with python-json parsing of `compose ps`) was both too
# short AND fragile.
if ! "${COMPOSE[@]}" -f "$COMPOSE_FILE" up -d --wait --wait-timeout 180; then
  echo "ERROR: Postgres did not become healthy within 180 sec. Recent container logs:"
  "${COMPOSE[@]}" -f "$COMPOSE_FILE" logs --tail 80 postgres 2>&1 || true
  exit 1
fi

echo ">>> Postgres healthy (port ${CRS_DB_PORT:-5432})."
