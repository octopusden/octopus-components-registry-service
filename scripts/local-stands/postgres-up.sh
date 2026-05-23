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
# Plain `up -d` without `--wait`: TC agents here delegate `docker compose`
# through podman to the v1 `docker-compose` binary which silently ignores
# `--wait` (prints help and exits 0 without starting the container).
# Compose v2's `--wait` is the cleaner API, but until every agent has v2
# we poll explicitly using `docker inspect` — works on both v1 and v2 and
# doesn't depend on `compose ps --format json` shape.
"${COMPOSE[@]}" -f "$COMPOSE_FILE" up -d

echo ">>> waiting for Postgres healthcheck (up to 180 sec)..."
# Resolve the postgres container ID once. `compose ps -q postgres` returns
# the bare container id on both v1 and v2.
CID=$("${COMPOSE[@]}" -f "$COMPOSE_FILE" ps -q postgres 2>/dev/null | head -1 || true)
if [ -z "$CID" ]; then
  echo "ERROR: postgres container not found after 'compose up -d'."
  "${COMPOSE[@]}" -f "$COMPOSE_FILE" ps || true
  exit 1
fi

# 90 iterations × 2 sec = 180 sec total. Generous enough for cold-cache
# postgres:16 pull + volume init on a podman rootless agent (line in TC
# log: `Pulling postgres (postgres:16)... 17s`, then volume create +
# container start + start_period 10s + first pg_isready can land near
# 60-90s in practice).
for i in $(seq 1 90); do
  health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$CID" 2>/dev/null || echo unknown)
  if [ "$health" = "healthy" ]; then
    echo ">>> Postgres healthy (port ${CRS_DB_PORT:-5432}, container $CID, after ${i}×2s)."
    exit 0
  fi
  if [ $((i % 10)) -eq 0 ]; then
    echo "    ${i}/90 iterations  health=$health"
  fi
  sleep 2
done

echo "ERROR: Postgres did not become healthy within 180 sec. Recent container logs:"
"${COMPOSE[@]}" -f "$COMPOSE_FILE" logs --tail 80 postgres 2>&1 || true
exit 1
