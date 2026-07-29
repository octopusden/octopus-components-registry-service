#!/usr/bin/env bash
#
# Publishes PIT mutation numbers as TeamCity build statistics, so the server draws them as charts over
# builds (Statistics tab). That trend is the reason the mutation analysis runs in TeamCity at all: the
# per-PR check lives on GitHub (reporting, not blocking a merge), while TeamCity keeps the browsable HTML
# report and the history.
#
# Usage (as a TeamCity script step, after the Gradle step):
#   scripts/teamcity/report-mutation-stats.sh [path/to/mutations.xml]
#
# With no argument the report is located by search rather than by a fixed path: the Gradle step runs in
# %WORK_DIR%, which is defined server-side on the shared build template and is therefore not knowable
# from this repository.
#
# Reporting only — never fails the build. A missing report is surfaced as a TeamCity warning, because
# the build result is owned by the `pitest` task itself (it applies mutationThreshold/coverageThreshold);
# this step must not turn a threshold pass into a red build because of a path problem.

set -uo pipefail

report=${1:-}

if [ -z "$report" ]; then
    report=$(find . -path '*/build/reports/pitest/mutations.xml' -print -quit 2>/dev/null)
fi

if [ -z "$report" ] || [ ! -f "$report" ]; then
    echo "##teamcity[message text='mutations.xml not found - no mutation statistics reported' status='WARNING']"
    exit 0
fi

# PIT marks every mutation with detected='true|false'. `detected` — not status='KILLED' — is what the
# mutation score and mutationThreshold are computed from: a mutant that hangs is TIMED_OUT and still
# detected, so counting KILLED alone under-reports.
#
# The same counting exists in .github/workflows/mutation.yml, which writes a job summary instead of
# TeamCity service messages. Deliberate duplication for two different CI consumers — but they diverged
# once already, so a change here belongs there in the same commit.
total=$(grep -c '<mutation ' "$report")
detected=$(grep -c "detected='true'" "$report")
killed=$(grep -c "status='KILLED'" "$report")
timed_out=$(grep -c "status='TIMED_OUT'" "$report")
survived=$(grep -c "status='SURVIVED'" "$report")
no_coverage=$(grep -c "status='NO_COVERAGE'" "$report")

if [ "$total" -le 0 ]; then
    echo "##teamcity[message text='mutations.xml contains no mutations - nothing to report' status='WARNING']"
    exit 0
fi

# Track the number the floor is checked against, which is not `detected * 100 / total`: shell division
# truncates, so that form reads a point low on most fractional scores. Round half up instead, and cap at
# 99 the way PIT does, so a near-sweep is not charted as a clean one.
#
# Verified against the pinned engine (pitest 1.16.1: PercentageCalculator.getPercentage(total, detected),
# the value MutationStatistics.getPercentageDetected() hands to mutationThreshold) by comparing every
# count up to total=2000 — 2005000 pairs. This form matches all but 54 of them; truncation missed 987536.
# The residue is unavoidable rather than sloppy: PIT computes `100f / total * detected` in single
# precision, so a score landing on an exact half can fall either way there (15/24 gives 62, not 63).
# Chart values can therefore sit a point below PIT's own on an exact half. Read the report, not the
# chart, when a build is that close to the floor.
if [ "$detected" -eq "$total" ]; then
    score=100
else
    score=$(((detected * 200 + total) / (total * 2)))
    if [ "$score" -gt 99 ]; then
        score=99
    fi
fi

echo "##teamcity[buildStatisticValue key='mutationScore' value='$score']"
echo "##teamcity[buildStatisticValue key='mutationsTotal' value='$total']"
echo "##teamcity[buildStatisticValue key='mutationsDetected' value='$detected']"
echo "##teamcity[buildStatisticValue key='mutationsKilled' value='$killed']"
echo "##teamcity[buildStatisticValue key='mutationsTimedOut' value='$timed_out']"
echo "##teamcity[buildStatisticValue key='mutationsSurvived' value='$survived']"
echo "##teamcity[buildStatisticValue key='mutationsNoCoverage' value='$no_coverage']"

# The four statuses above are what a healthy run produces; the engine has more (NON_VIABLE,
# MEMORY_ERROR, RUN_ERROR, …). Report the remainder as a warning rather than letting the charted
# buckets quietly fail to add up to mutationsTotal.
other=$((total - killed - timed_out - survived - no_coverage))
if [ "$other" -ne 0 ]; then
    echo "##teamcity[message text='$other mutations have a status outside KILLED/TIMED_OUT/SURVIVED/NO_COVERAGE (non-viable or an engine error) - see the report' status='WARNING']"
fi

# Also put the headline on the build itself, so the overview answers "how did it move" without opening
# the report or the chart.
echo "##teamcity[buildStatus text='{build.status.text}; mutation score ${score}% ($detected/$total detected, $survived survived, $no_coverage not covered)']"
