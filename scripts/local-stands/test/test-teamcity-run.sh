#!/usr/bin/env bash
# Static-text contract test for scripts/local-stands/teamcity-run.sh.
#
# Pins the prod-alignment Spring property overrides that MUST appear in BOTH
# the baseline AND candidate JVM start commands. BOTH dev profile yamls
# loaded via --spring.config.additional-location set the same dev values:
#   components-registry-service-server/dev/application-dev.yml
#   components-registry-service-server/dev/application-dev-vcs-local.yml
# Each writes components-registry.version-name.{service-branch,service,
# minor} = serviceCBranch/serviceC/minorC. Prod (cloud-prod) and QA
# (cloud-qa) profiles do not activate either of these and inherit *Cards
# values from service-config/components-registry-service.yml.
#
# Why version-name affects the compat surface — `version-name.*` are
# template-placeholder NAMES used by KotlinVersionFormatter and
# JiraComponentVersionFormatter.normalizeVersion when matching DSL
# `releaseVersionFormat`/`majorVersionFormat` patterns (e.g.
# `$major.$minor.$service`) against concrete versions. With mismatched
# token names, matchesFormat / normalizeVersion / getPatchedFormat can
# resolve a given version into a different DSL version-range, which shifts
# which `moduleConfigurations` entry ends up at index [0] for components
# that mix AUTO and MANUAL escrow ranges (e.g. wscardsmodel).
# BaseComponentController picks [0]-th moduleConfiguration verbatim when
# rendering a versionless Component — so the local-baseline JAR can return
# escrow.generation=MANUAL while prod V1 (same code, same DSL revision)
# returns AUTO. Numeric range-string parsing in NumericVersionFactory /
# VersionRangeImpl is independent of version-name — only the format
# template/placeholder pipeline is affected.
#
# The CLI overrides restore prod-equivalent values at the highest Spring
# property precedence, defeating both dev yamls without removing them from
# the profile chain (dev-vcs-local is still needed for the file:// VCS
# root override).
#
# Note on supportedGroupIds / supportedSystems: the dev yaml sets these at
# the YAML root (`supportedGroupIds: ...`), but the application code reads
# them through ConfigHelper at the nested path
# `components-registry.supportedGroupIds`. The root-level dev values do not
# bind to the nested code path, so they have no observable effect on the
# compat surface and do NOT need a CLI override.
#
# Static-text check: parses the script text and counts occurrences. No JVM,
# no live stand. Runs in <1s.
#
# Usage:
#   bash scripts/local-stands/test/test-teamcity-run.sh
#   → exit 0 on green, non-zero on any failure.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEAMCITY_RUN_SH="$SCRIPT_DIR/../teamcity-run.sh"

if [ ! -f "$TEAMCITY_RUN_SH" ]; then
  echo "FAIL: $TEAMCITY_RUN_SH not found"
  exit 2
fi

# Each prod-alignment override must appear at least TWICE in teamcity-run.sh
# — once in the baseline `nohup ... -jar "$BASELINE_JAR" ...` block, once
# in the candidate block. Counting `grep -c -F` matches lines; with one
# flag per CLI line, line count and occurrence count agree.
REQUIRED_FLAGS=(
  "--components-registry.version-name.service-branch=serviceCardsBranch"
  "--components-registry.version-name.service=serviceCards"
  "--components-registry.version-name.minor=minorCards"
)

PASS=0
FAIL=0
FAIL_NAMES=()

run_case() {
  local name="$1"; shift
  if ( "$@" ); then
    PASS=$((PASS + 1))
    printf '  PASS  %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    FAIL_NAMES+=("$name")
    printf '  FAIL  %s\n' "$name"
  fi
}

count_in_file_fixed() {
  local needle="$1" file="$2"
  grep -c -F -- "$needle" "$file"
}

count_in_file_regex() {
  local pattern="$1" file="$2"
  grep -c -E -- "$pattern" "$file"
}

case_each_flag_appears_at_least_twice() {
  local rc=0
  for flag in "${REQUIRED_FLAGS[@]}"; do
    local n
    n=$(count_in_file_fixed "$flag" "$TEAMCITY_RUN_SH")
    if [ "$n" -lt 2 ]; then
      echo "    flag missing or single-stand-only (count=$n, expected >=2): $flag"
      rc=1
    fi
  done
  return $rc
}

# Sanity guard: dev-profile values must NOT be hard-coded in teamcity-run.sh
# itself. They live in components-registry-service-server/dev/application-
# dev.yml; this guard catches a future drift where someone copy-pastes the
# dev values into the start command.
#
# Boundary heuristic: each dev value ends in `C`. The matching prod value
# starts with `Cards`. To match `...=minorC` followed by space, backslash,
# newline, end-of-line, or quote — but NOT followed by `a` (the start of
# `Cards`) — anchor the regex on a NON-LETTER boundary character or
# end-of-line. This catches all the realistic positions the dev value
# could land (mid-CLI continuation, last arg with no trailing `\`, inside
# an HEREDOC, etc.) without false-positives against the prod literal.
case_dev_override_values_not_hardcoded() {
  local forbidden=(
    'version-name\.minor=minorC([^A-Za-z]|$)'
    'version-name\.service=serviceC([^A-Za-z]|$)'
    'version-name\.service-branch=serviceCBranch([^A-Za-z]|$)'
  )
  local rc=0
  for pattern in "${forbidden[@]}"; do
    local n
    n=$(count_in_file_regex "$pattern" "$TEAMCITY_RUN_SH")
    if [ "$n" -ne 0 ]; then
      echo "    dev-profile value leaked into teamcity-run.sh (count=$n): $pattern"
      rc=1
    fi
  done
  return $rc
}

# git-mode wiring guard (CANDIDATE_MODE=git): the no-migration CLI overrides and
# the git-mode known-deltas selection must stay present, else id18 ([1.8]) would
# silently fall back to db-mode behaviour and stop guarding the deploy-as-no-op
# invariant (v3 with no migration == 2.0.87 for v1/v2/v3).
case_git_mode_wiring_present() {
  local needles=(
    '--components-registry.auto-migrate=false'
    '--components-registry.default-source=git'
    'known-deltas-git.json'
    'CANDIDATE_MODE="${CANDIDATE_MODE:-db}"'
  )
  local rc=0
  for needle in "${needles[@]}"; do
    local n
    n=$(count_in_file_fixed "$needle" "$TEAMCITY_RUN_SH")
    if [ "$n" -lt 1 ]; then
      echo "    git-mode wiring missing (count=$n): $needle"
      rc=1
    fi
  done
  return $rc
}

echo "=== teamcity-run.sh prod-alignment overrides ==="
run_case "each version-name override appears in both baseline and candidate start commands" case_each_flag_appears_at_least_twice
run_case "dev-profile values not hard-coded in teamcity-run.sh" case_dev_override_values_not_hardcoded
run_case "git-mode wiring present (CANDIDATE_MODE overrides + git known-deltas)" case_git_mode_wiring_present

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
