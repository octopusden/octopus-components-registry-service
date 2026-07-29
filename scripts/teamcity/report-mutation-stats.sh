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

score=$((detected * 100 / total))

echo "##teamcity[buildStatisticValue key='mutationScore' value='$score']"
echo "##teamcity[buildStatisticValue key='mutationsTotal' value='$total']"
echo "##teamcity[buildStatisticValue key='mutationsDetected' value='$detected']"
echo "##teamcity[buildStatisticValue key='mutationsKilled' value='$killed']"
echo "##teamcity[buildStatisticValue key='mutationsTimedOut' value='$timed_out']"
echo "##teamcity[buildStatisticValue key='mutationsSurvived' value='$survived']"
echo "##teamcity[buildStatisticValue key='mutationsNoCoverage' value='$no_coverage']"

# Also put the headline on the build itself, so the overview answers "how did it move" without opening
# the report or the chart.
echo "##teamcity[buildStatus text='{build.status.text}; mutation score ${score}% ($detected/$total detected, $survived survived, $no_coverage not covered)']"
