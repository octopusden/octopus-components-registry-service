#!/usr/bin/env bash
# Self-tests for the three shell helpers in verify.sh:
#   - check_migration_health   — log-summary parser + polluted-run guard
#   - kill_port                — port-scoped JVM kill
#   - wait_port_free           — bounded-wait readiness poll
#
# Pure bash, no bats / no extra deps. Exercises the real functions defined in
# verify.sh (the file is sourced; verify.sh short-circuits its orchestration
# block when BASH_SOURCE != $0). Mocks `lsof` / `kill` / `sleep` via shell
# function definitions, which override external commands of the same name
# within the test process.
#
# Out of scope (intentionally — these live in verify.sh's orchestration block,
# which the source-guard skips):
#   - the hard-fail `exit 3` when `wait_port_free` times out after the
#     restart-kill (verify.sh:201-207). The unit test pins the return value of
#     `wait_port_free` itself; coupling it to the orchestrator would force
#     test-only refactoring of that block. Covered by manual smoke
#     (`verify.sh --restart` end-to-end).
#
# Usage:
#   bash scripts/local-stands/test/test-verify-lib.sh
#   → exit 0 on green, non-zero on any failure.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SH="$SCRIPT_DIR/../verify.sh"

if [ ! -f "$VERIFY_SH" ]; then
  echo "FAIL: $VERIFY_SH not found"
  exit 2
fi

# Bring in helpers. verify.sh detects sourcing via BASH_SOURCE != $0 and
# returns 0 before its orchestration block. set -e is left intact from verify.sh.
# Re-disable so individual assertions can record failures rather than bail.
# shellcheck disable=SC1090
source "$VERIFY_SH"
set +e

# Each test runs in a subshell so function redefinitions ("mocks") don't bleed
# between cases.
PASS=0
FAIL=0
FAIL_NAMES=()

run_case() {
  local name="$1"; shift
  # Stage: the rest of the args form a command to execute in a subshell.
  if ( "$@" ); then
    PASS=$((PASS + 1))
    printf '  PASS  %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    printf '  FAIL  %s\n' "$name"
  fi
}

assert_eq() {
  local expected="$1" actual="$2" label="${3:-value}"
  if [ "$expected" = "$actual" ]; then
    return 0
  fi
  echo "    $label: expected [$expected] got [$actual]"
  return 1
}

assert_contains() {
  local needle="$1" haystack="$2" label="${3:-output}"
  case "$haystack" in
    *"$needle"*) return 0 ;;
  esac
  echo "    $label: expected to contain [$needle], got [$haystack]"
  return 1
}

# ---------- check_migration_health ----------
# Each case writes a fixture log to a fresh temp file and runs the real function
# from verify.sh against it.

TMPD="$(mktemp -d -t crs-verify-tests.XXXXXX)"
trap 'rm -rf "$TMPD"' EXIT

write_log() {
  local path="$1"; shift
  printf '%s\n' "$@" > "$path"
}

case_clean_inner_marker() {
  local log="$TMPD/log-clean.txt"
  write_log "$log" \
    "2026-05-16 10:00 INFO  some other stuff" \
    "2026-05-16 10:05 INFO  Migration complete: total=948, migrated=948, failed=0, skipped=0"
  local out
  out=$(check_migration_health "$log")
  assert_contains "total=948" "$out" "summary line" || return 1
  assert_contains "failed=0" "$out" "summary line" || return 1
  case "$out" in
    *POLLUTED*) echo "    unexpectedly flagged POLLUTED on clean log"; return 1 ;;
  esac
  return 0
}

case_clean_inner_marker_with_duration() {
  local log="$TMPD/log-clean-duration.txt"
  write_log "$log" \
    "2026-05-16 10:05 INFO  Migration complete in 12345 ms: total=948, migrated=948, failed=0, skipped=0"
  local out
  out=$(check_migration_health "$log")
  assert_contains "total=948" "$out" "duration-form summary line" || return 1
  assert_contains "failed=0" "$out" "duration-form summary line" || return 1
  return 0
}

case_polluted_inner_marker_exits_4() {
  local log="$TMPD/log-polluted-inner.txt"
  write_log "$log" \
    "2026-05-16 10:00 INFO  Failed to migrate component 'comp-foo'" \
    "2026-05-16 10:00 INFO  Failed to migrate component 'comp-bar'" \
    "2026-05-16 10:05 INFO  Migration complete: total=948, migrated=925, failed=23, skipped=0"
  local exit_code
  ( ALLOW_PARTIAL=0 check_migration_health "$log" >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "4" "$exit_code" "exit code"
}

case_polluted_allows_partial() {
  local log="$TMPD/log-polluted-inner.txt"
  write_log "$log" \
    "2026-05-16 10:00 INFO  Failed to migrate component 'comp-foo'" \
    "2026-05-16 10:05 INFO  Migration complete: total=948, migrated=947, failed=1, skipped=0"
  local exit_code
  ( ALLOW_PARTIAL=1 check_migration_health "$log" >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "exit code with ALLOW_PARTIAL=1"
}

case_outer_wrapper_only_polluted() {
  # Fallback path: only the outer "Auto-migrate complete:" marker present.
  # This case is also a regression guard for the greedy-left sed bug:
  # "10 failed" would yield "0" under the wrong pattern.
  local log="$TMPD/log-outer.txt"
  write_log "$log" \
    "2026-05-16 10:00 INFO  Failed to migrate component 'comp-x'" \
    "2026-05-16 10:05 INFO  Auto-migrate complete: 935 migrated, 0 skipped, 10 failed"
  local out exit_code
  out=$( ALLOW_PARTIAL=1 check_migration_health "$log" 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "exit code with ALLOW_PARTIAL=1" || return 1
  # The greedy-left bug would mis-parse failed=0 here. Anchor on the exact
  # rendered summary line.
  assert_contains "failed=10" "$out" "outer-wrapper failed count" || return 1
  assert_contains "migrated=935" "$out" "outer-wrapper migrated count" || return 1
  return 0
}

case_no_marker_returns_0() {
  local log="$TMPD/log-none.txt"
  write_log "$log" "2026-05-16 10:00 INFO  Started ComponentRegistryServiceApplication"
  local exit_code
  ( ALLOW_PARTIAL=0 check_migration_health "$log" >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "no marker → return 0"
}

case_missing_log_returns_0() {
  local exit_code
  ( ALLOW_PARTIAL=0 check_migration_health "$TMPD/does-not-exist.log" >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "missing log → return 0"
}

# ---------- kill_port ----------
# Mock lsof + kill. lsof returns synthetic PIDs in a controllable sequence;
# kill captures the calls so we can assert on them. The function under test
# pauses between TERM and KILL with `sleep 1`; mock sleep to no-op so the
# test runs fast.

case_kill_port_no_listener() {
  # lsof returns empty → kill_port should return 0 without calling kill.
  lsof() { return 0; }
  kill() { echo "KILL_CALLED: $*"; }
  sleep() { :; }
  local out
  out=$( kill_port 31337 2>&1 )
  assert_contains "" "" "noop" >/dev/null
  case "$out" in
    *KILL_CALLED*) echo "    kill should not have been called"; return 1 ;;
  esac
  return 0
}

case_kill_port_terminates_after_TERM() {
  # First lsof call returns a PID. After the (mocked) sleep loop, the same
  # PID stays present for the initial check + 2 loop iterations, then
  # disappears — kill_port should exit before the SIGKILL branch.
  #
  # Counter is on disk because kill_port reads lsof via `$(lsof ...)`, and
  # command substitution creates a subshell where in-process variable
  # mutations would never be seen by the next call.
  local counter="$TMPD/lsof-counter"
  echo 0 > "$counter"
  lsof() {
    local n
    n=$(cat "$counter")
    n=$((n + 1))
    echo "$n" > "$counter"
    if [ "$n" -le 3 ]; then echo "9999"; fi
  }
  kill() { echo "TERM:$*"; }
  sleep() { :; }
  local out
  out=$( kill_port 31337 2>&1 )
  assert_contains "TERM:9999" "$out" "TERM call recorded" || return 1
  case "$out" in
    *TERM:-9*) echo "    kill_port escalated to -9 prematurely"; return 1 ;;
    *"still alive"*) echo "    kill_port reached the still-alive SIGKILL branch"; return 1 ;;
  esac
  return 0
}

case_kill_port_escalates_to_SIGKILL_when_still_alive() {
  # PID stays present forever. The function loops 10 times (sleep 1 each),
  # then calls `kill -9`. Mock sleep so the loop is instant.
  lsof() { echo "9999"; }
  kill() { echo "KILL:$*"; }
  sleep() { :; }
  local out
  out=$( kill_port 31337 2>&1 )
  assert_contains "KILL:9999" "$out" "TERM call" || return 1
  assert_contains "KILL:-9 9999" "$out" "SIGKILL escalation" || return 1
  assert_contains "still alive" "$out" "escalation log" || return 1
  return 0
}

case_kill_port_passes_correct_argv_to_lsof() {
  # Regression pin for PR #220: the previous syntax was
  #   lsof -ti -sTCP:LISTEN tcp:$port
  # which on macOS treats `tcp:$port` as a *filename* and returns empty —
  # the function silently killed nothing and verify.sh raced against the
  # surviving JVM. The fix uses `-t -i tcp:$port -sTCP:LISTEN`. Pin both
  # arg forms by recording the actual argv on each invocation.
  local argv_log="$TMPD/lsof-argv"
  : > "$argv_log"
  lsof() {
    printf '%s\n' "$*" >> "$argv_log"
    # Return empty on the first call (so kill_port early-exits without
    # going into the TERM loop, keeping the test fast and the argv-record
    # short for the assertion).
    return 0
  }
  kill() { :; }
  sleep() { :; }
  ( kill_port 31337 >/dev/null 2>&1 )

  # The argv must contain `-t` AND `-i tcp:31337` AND `-sTCP:LISTEN` —
  # in any order, but each as separate tokens, NOT the buggy fused
  # `-ti -sTCP:LISTEN tcp:31337` ordering that real macOS lsof rejected.
  local recorded
  recorded=$(cat "$argv_log")
  assert_contains "-t" "$recorded" "lsof argv contains -t flag (separate token)" || return 1
  assert_contains "-i tcp:31337" "$recorded" "lsof argv pairs -i with the port" || return 1
  assert_contains "-sTCP:LISTEN" "$recorded" "lsof argv filters to LISTEN state" || return 1
  case "$recorded" in
    *"-ti"*)
      echo "    REGRESSION: lsof argv still contains the fused -ti flag — PR #220 broke"
      return 1
      ;;
  esac
  case "$recorded" in
    *"-sTCP:LISTEN tcp:"*)
      echo "    REGRESSION: argv order has -sTCP:LISTEN immediately before tcp:port"
      echo "    (matches the broken pre-PR-#220 form where macOS treats 'tcp:port' as filename)"
      return 1
      ;;
  esac
  return 0
}

case_kill_port_no_lsof_returns_0() {
  # `command -v lsof` returns non-zero (lsof missing). kill_port logs a warning
  # and exits 0 without touching anything.
  command() {
    # shellcheck disable=SC2317
    if [ "$1" = "-v" ] && [ "$2" = "lsof" ]; then
      return 1
    fi
    # Forward all other `command` invocations to the builtin.
    builtin command "$@"
  }
  local exit_code
  ( kill_port 31337 >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "exit when lsof missing"
}

# ---------- wait_port_free ----------
# wait_port_free calls health(port) and (if available) lsof. Health returns 0
# when service responds. The function returns 0 as soon as both report free,
# and 1 if neither reports free within the cap.

case_wait_port_free_returns_0_immediately() {
  # health says port is not responding, lsof says nothing is listening → free.
  health() { return 1; }
  lsof() { return 1; }
  sleep() { :; }
  local exit_code
  ( wait_port_free 31337 5 >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "immediate-free returns 0"
}

case_wait_port_free_returns_1_on_timeout() {
  # health always responsive → port stays held → cap reached → return 1.
  health() { return 0; }
  lsof() { echo ""; return 0; }
  sleep() { :; }
  local exit_code
  ( wait_port_free 31337 3 >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "1" "$exit_code" "timeout returns 1"
}

case_wait_port_free_eventually_becomes_free() {
  # First 2 iterations: port held; iteration 3: free.
  local _iter=0
  health() {
    _iter=$((_iter + 1))
    [ "$_iter" -lt 3 ]
  }
  lsof() { return 1; }
  sleep() { :; }
  local exit_code
  ( wait_port_free 31337 5 >/dev/null 2>&1 )
  exit_code=$?
  assert_eq "0" "$exit_code" "becomes-free returns 0"
}

# ---------- runner ----------

echo "=== check_migration_health ==="
run_case "clean run, inner marker → no POLLUTED" case_clean_inner_marker
run_case "clean run, inner marker with duration → no POLLUTED" case_clean_inner_marker_with_duration
run_case "polluted run, inner marker → exit 4"   case_polluted_inner_marker_exits_4
run_case "polluted + ALLOW_PARTIAL=1 → exit 0"   case_polluted_allows_partial
run_case "outer wrapper only, '10 failed' parsed exactly" case_outer_wrapper_only_polluted
run_case "no marker → return 0"                   case_no_marker_returns_0
run_case "missing log → return 0"                 case_missing_log_returns_0

echo "=== kill_port ==="
run_case "no listener → noop"                     case_kill_port_no_listener
run_case "argv matches PR #220 fix (macOS)"       case_kill_port_passes_correct_argv_to_lsof
run_case "TERM terminates → no SIGKILL"           case_kill_port_terminates_after_TERM
run_case "stubborn process → SIGKILL escalation"  case_kill_port_escalates_to_SIGKILL_when_still_alive
run_case "lsof missing → return 0"                case_kill_port_no_lsof_returns_0

echo "=== wait_port_free ==="
run_case "already free → return 0"                case_wait_port_free_returns_0_immediately
run_case "timeout → return 1"                     case_wait_port_free_returns_1_on_timeout
run_case "becomes free within cap → return 0"     case_wait_port_free_eventually_becomes_free

echo
echo "=== summary ==="
echo "  pass: $PASS"
echo "  fail: $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "  failed cases:"
  for n in "${FAIL_NAMES[@]}"; do echo "    - $n"; done
  exit 1
fi
exit 0
