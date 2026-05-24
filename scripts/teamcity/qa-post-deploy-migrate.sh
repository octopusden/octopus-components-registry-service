#!/bin/bash
# Post-deploy: trigger Git→DB migration on the freshly-deployed QA pod.
#
# Designed to be tolerant of "this build does not need / cannot run migration":
#   - 404 on /admin/migration-status  → pre-v3 build deployed, exit 0 (skip)
#   - git == 0                        → nothing left to migrate, exit 0 (skip)
# It exits non-zero only when migration was *attempted* and ended in FAILED
# state (or pod never became ready). That matches the user contract: "if there
# is no new code, just skip — don't fail".
#
# Required env:
#   CRS_BASE_URL          e.g. https://components-registry.qa.example/
#   CRS_ADMIN_TOKEN       Raw Keycloak JWT with IMPORT_DATA role — the token
#                         value ONLY, without an `Authorization: ` prefix and
#                         without a leading `Bearer ` scheme word. The script
#                         adds `Authorization: Bearer <token>` itself. A
#                         leading `Bearer ` is stripped defensively in case
#                         the TC parameter was provisioned with the prefix.
#
# Optional env:
#   READINESS_TIMEOUT_SEC  default 300
#   MIGRATE_TIMEOUT_SEC    default 1800 (30 min)
#   POLL_INTERVAL_SEC      default 10

set -euo pipefail

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

: "${CRS_BASE_URL:?CRS_BASE_URL is required (e.g. https://crs.qa.example)}"
: "${CRS_ADMIN_TOKEN:?CRS_ADMIN_TOKEN is required (Bearer JWT with IMPORT_DATA role)}"

# TC substitutes `%PARAM_NAME%` references at runtime; if the project-level
# parameter is not provisioned on the TC server, the literal `%PARAM_NAME%`
# arrives here as a non-empty string and the `:?` check above is silent.
# Detect that and bail out with a clear "TC parameter missing" message
# instead of spending 300s polling a bogus URL.
case "$CRS_BASE_URL" in
  %*%)
    log "ERROR: CRS_BASE_URL still contains an unresolved TC placeholder ($CRS_BASE_URL)."
    log "Provision the project-level parameter (e.g. CRS_QA_DEV_BASE_URL) on the TC server."
    exit 1
    ;;
esac
case "$CRS_ADMIN_TOKEN" in
  %*%)
    log "ERROR: CRS_ADMIN_TOKEN still contains an unresolved TC placeholder."
    log "Provision the project-level password parameter (e.g. CRS_QA_ADMIN_TOKEN) on the TC server."
    exit 1
    ;;
esac

# Defensively strip a leading `Bearer ` (case-insensitive) so the parameter
# can be provisioned either as the raw JWT (preferred) or as the full
# `Bearer <jwt>` header value without the script producing the broken
# `Authorization: Bearer Bearer <jwt>` chain. The contract in the docstring
# remains "raw JWT only" — this is just an operator-error safety net.
case "$CRS_ADMIN_TOKEN" in
  [Bb][Ee][Aa][Rr][Ee][Rr]\ *) CRS_ADMIN_TOKEN="${CRS_ADMIN_TOKEN#* }" ;;
esac

# JSON parsing uses python3 (see jget below). Most modern Linux TC agents
# ship python3 by default, but slim Alpine-based agents may not. Fail fast
# with a clear message instead of crashing mid-poll with `python3: not found`.
if ! command -v python3 >/dev/null 2>&1; then
  log "ERROR: python3 is required by this script (used to parse JSON responses)."
  log "Install it on the TC agent or pin the build to an agent label that has it."
  exit 1
fi

READINESS_TIMEOUT_SEC="${READINESS_TIMEOUT_SEC:-300}"
MIGRATE_TIMEOUT_SEC="${MIGRATE_TIMEOUT_SEC:-1800}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-10}"

BASE="${CRS_BASE_URL%/}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# curl wrapper: -sS keeps it quiet but reports errors; -o saves body;
# -w writes status code to stdout; -m bounds per-call time.
http_get() {
  local url="$1" out="$2"
  curl -sS -m 30 -o "$out" -w '%{http_code}' \
    -H "Authorization: Bearer ${CRS_ADMIN_TOKEN}" \
    -H 'Accept: application/json' \
    "$url"
}

http_post() {
  local url="$1" out="$2"
  curl -sS -m 30 -o "$out" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer ${CRS_ADMIN_TOKEN}" \
    -H 'Accept: application/json' \
    "$url"
}

# Anonymous probe — /actuator/health is permitAll (see WebSecurityConfig).
http_get_anon() {
  local url="$1" out="$2"
  curl -sS -m 10 -o "$out" -w '%{http_code}' "$url"
}

# Extract a JSON number/string field with python3 — robust vs grep/sed.
# Usage: jget <file> <jq-style dotted path, only top-level keys supported>
jget() {
  local file="$1" key="$2"
  python3 - "$file" "$key" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
print(data.get(sys.argv[2], ''))
PY
}

###############################################################################
# 1. Wait for readiness.
###############################################################################
log "Waiting up to ${READINESS_TIMEOUT_SEC}s for ${BASE}/actuator/health/readiness"
deadline=$(( $(date +%s) + READINESS_TIMEOUT_SEC ))
while :; do
  body="$WORK_DIR/readiness.json"
  code="$(http_get_anon "${BASE}/actuator/health/readiness" "$body" || echo 000)"
  if [ "$code" = "200" ]; then
    log "Readiness OK"
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "ERROR: pod did not become ready within ${READINESS_TIMEOUT_SEC}s (last code=$code)"
    exit 1
  fi
  sleep "$POLL_INTERVAL_SEC"
done

###############################################################################
# 2. Log deployed version (no auth needed).
###############################################################################
info="$WORK_DIR/info.json"
info_code="$(http_get_anon "${BASE}/rest/api/4/info" "$info" || echo 000)"
if [ "$info_code" = "200" ]; then
  version="$(jget "$info" version)"
  log "Deployed version: ${version:-<unknown>}"
else
  log "WARN: /rest/api/4/info returned HTTP ${info_code} — continuing"
fi

###############################################################################
# 3. Check migration-status. Tolerant skip on 404 (pre-v3 build).
###############################################################################
status="$WORK_DIR/migration-status.json"
status_code="$(http_get "${BASE}/rest/api/4/admin/migration-status" "$status")"
case "$status_code" in
  200)
    git_count="$(jget "$status" git)"
    db_count="$(jget "$status" db)"
    total="$(jget "$status" total)"
    log "Migration status: git=${git_count} db=${db_count} total=${total}"
    if [ "${git_count:-0}" = "0" ]; then
      log "Nothing left to migrate — skip"
      exit 0
    fi
    ;;
  404)
    log "Endpoint /admin/migration-status is 404 — pre-v3 build deployed, skip"
    exit 0
    ;;
  401|403)
    log "ERROR: auth rejected (HTTP $status_code). CRS_ADMIN_TOKEN expired / lacks IMPORT_DATA?"
    log "Response body suppressed (may echo auth headers)."
    exit 1
    ;;
  *)
    log "ERROR: unexpected status from /admin/migration-status: HTTP $status_code"
    log "Response body suppressed."
    exit 1
    ;;
esac

###############################################################################
# 4. Kick off async migrate. 202 = newly started, 409 = attach-to-running.
###############################################################################
start="$WORK_DIR/migrate-start.json"
start_code="$(http_post "${BASE}/rest/api/4/admin/migrate" "$start")"
case "$start_code" in
  202) log "Migration started" ;;
  409)
    # Two distinct 409 shapes from AdminControllerV4:
    #   same-kind  → MigrationJobResponse  (has `state`)        → safe to attach
    #   cross-kind → MigrationConflictResponse (has `activeKind`) → other kind is
    #     holding the gate; there is no components job to poll, /migrate/job will
    #     404. Bail out with a clear message instead of looping forever.
    active_kind="$(jget "$start" activeKind)"
    if [ -n "$active_kind" ] && [ "$active_kind" != "COMPONENTS" ]; then
      conflict_code="$(jget "$start" code)"
      log "ERROR: cross-kind migration conflict (active=$active_kind, code=$conflict_code)"
      log "Another migration is holding the lifecycle gate. Wait for it to finish, then re-run [3.1]."
      exit 1
    fi
    log "Migration already running — attaching to existing job"
    ;;
  404) log "POST /admin/migrate is 404 — pre-v3 build, skip"; exit 0 ;;
  401|403) log "ERROR: auth rejected on POST /admin/migrate (HTTP $start_code)"; log "Response body suppressed."; exit 1 ;;
  *) log "ERROR: unexpected HTTP $start_code from POST /admin/migrate"; log "Response body suppressed."; exit 1 ;;
esac

###############################################################################
# 5. Poll job until terminal state.
###############################################################################
deadline=$(( $(date +%s) + MIGRATE_TIMEOUT_SEC ))
job="$WORK_DIR/migrate-job.json"
last_phase=""
last_migrated=""
while :; do
  job_code="$(http_get "${BASE}/rest/api/4/admin/migrate/job" "$job")"
  if [ "$job_code" != "200" ]; then
    log "ERROR: GET /admin/migrate/job returned HTTP $job_code"
    cat "$job" || true
    exit 1
  fi
  state="$(jget "$job" state)"
  phase="$(jget "$job" phase)"
  migrated="$(jget "$job" migrated)"
  failed="$(jget "$job" failed)"
  skipped="$(jget "$job" skipped)"
  total_j="$(jget "$job" total)"
  if [ "$phase" != "$last_phase" ] || [ "$migrated" != "$last_migrated" ]; then
    log "Job: state=$state phase=$phase migrated=$migrated failed=$failed skipped=$skipped total=$total_j"
    last_phase="$phase"
    last_migrated="$migrated"
  fi
  case "$state" in
    COMPLETED)
      if [ "${failed:-0}" != "0" ]; then
        log "ERROR: migration COMPLETED with failed=$failed"
        cat "$job"
        exit 1
      fi
      log "Migration COMPLETED: migrated=$migrated skipped=$skipped"
      exit 0
      ;;
    FAILED)
      log "ERROR: migration FAILED"
      cat "$job"
      exit 1
      ;;
  esac
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "ERROR: migration did not finish within ${MIGRATE_TIMEOUT_SEC}s (last state=$state)"
    cat "$job" || true
    exit 1
  fi
  sleep "$POLL_INTERVAL_SEC"
done
