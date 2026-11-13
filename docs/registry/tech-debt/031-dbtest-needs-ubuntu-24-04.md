# TD-031: dbTest only passes on ubuntu-24.04 runners

## Status

Open · P2 · tracked in #558.

## Context

`ubuntu-latest` resolves to ubuntu-26.04 since 2026-11-12. On that image 209 of 876 `dbTest`
tests fail: a Testcontainers Postgres dies partway through the run (`unexpected postmaster
exit`) and every later Spring context fails in Docker discovery. Evidence in #556.

## Workaround applied

The quality and build jobs run on `ubuntu-24.04` because the octopus-base reusable workflows
pin that label from v3.4.1 on (octopus-base #249). CRS sets no `runs-on` of its own. Moving to
an octopus-base release that switches to ubuntu-26.04 (octopus-base #260) brings the failure
back.

## Removal

1. Find the cause on a 26.04 runner. The lead: `TESTCONTAINERS_RYUK_DISABLED=true` in
   `build.gradle`, and 28 test classes start `PostgreSQLContainer(...).apply { start() }` without
   ever stopping it, so containers pile up during one `dbTest` run.
2. Fix it, for example with one JVM-wide container in test-common or `stop()` in `@AfterAll`.
3. Show `quality/tests-coverage` passing on ubuntu-26.04, then delete this entry.

## References

- #556 (symptom), #558 (fix), octopus-base #249 and #260
- `build.gradle` (Ryuk setting), `.github/workflows/merge-gate.yml`, `.github/workflows/quality.yml`
