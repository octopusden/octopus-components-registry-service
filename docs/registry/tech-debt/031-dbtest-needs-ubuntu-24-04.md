# TD-031: dbTest only passes on ubuntu-24.04 runners

## Status

**Resolved** · P2 · #558. The server module's tests use Testcontainers 1.21.4, and the quality
jobs run on ubuntu-26.04 again.

## Context

`ubuntu-latest` resolves to ubuntu-26.04 since 2026-11-12. On that image 209 of 876 `dbTest`
tests fail: a Testcontainers Postgres dies partway through the run (`unexpected postmaster
exit`) and every later Spring context fails in Docker discovery. Evidence in #556.

## Cause

ubuntu-26.04 ships Docker 29, which rejects clients older than Docker API 1.40
(`client version 1.32 is too old. Minimum supported API version is 1.40`). The server module
took Testcontainers 1.19.3 from the Spring Boot 3.2.2 BOM; its docker-java speaks API 1.32.
The client and light-client modules already used 1.21.4 and passed their `dbTest` on the same
runner.

The earlier lead (Ryuk disabled, containers never stopped) was not the cause: with only the
Testcontainers version changed, the whole server `dbTest` passed on ubuntu-26.04
(run 43434113899, PR #559).

## Workaround applied (removed)

The quality jobs ran on `ubuntu-24.04`, the default `runs-on` of the octopus-base reusable
workflows from v3.4.1 on (octopus-base #249).

## Resolution

1. `components-registry-service-server/build.gradle` imports `testcontainers-bom:1.21.4` for
   tests, as `components-registry-service-client` does.
2. `.github/workflows/merge-gate.yml` and `.github/workflows/quality.yml` pass
   `runs-on: ubuntu-26.04` to the quality-gates workflow, so `dbTest` keeps running against
   Docker 29. The input can be dropped once octopus-base defaults to ubuntu-26.04
   (octopus-base #260).

TeamCity agents were never affected: they run Podman 4.9.4, which reports API 1.41.

## References

- #556 (symptom), #558 (fix), #559 (first run on ubuntu-26.04), octopus-base #249 and #260
- `components-registry-service-server/build.gradle`, `.github/workflows/merge-gate.yml`,
  `.github/workflows/quality.yml`
