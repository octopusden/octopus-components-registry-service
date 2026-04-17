# ft-db Testing — Plan & Status

Tracks the work for the `ft-db` Spring profile (H2 in-memory + auto-migrate) that downstream
FT environments use instead of running a full PostgreSQL. Opened as PR
[#148](https://github.com/octopusden/octopus-components-registry-service/pull/148).

## TDD rule

When a problem is identified in CRS:

1. Add or update a requirement entry in the matching `requirements-*.md` (MIG / SYS / RES).
2. Write a failing test that names the requirement ID in `@DisplayName`.
3. Commit the red test (`test: <ID> red`).
4. Fix the code, re-run → green. Commit the fix (`fix: <ID> green`).

## Status matrix

| ID | Task | Req ID | Status | Parallel | Dependency |
|----|------|--------|--------|:---:|------------|
| **T1** | Add `FlywayValidatePostgresStartupTest` — PostgreSQL testcontainer + Flyway V1–V3 + `ddl-auto=validate`. Addresses CodeRabbit P1 concern: does Hibernate's dialect-derived DDL for `system` (`@JdbcTypeCode(SqlTypes.ARRAY)` without `columnDefinition`) match the `text[]` column created by V1? | SYS-026 (new) | ⏳ Pending | ✅ | — |
| **T2** | Document `ft-db` profile in `docs/db-migration/deployment/dev-run.md` (section alongside "Auto-Migrate Mode"). | — | ⏳ Pending | ✅ | — |
| **T3** | Add H2 write-path sanity test — POST/PATCH against ft-db profile to verify `jsonb` columnDefinitions survive on H2 in PostgreSQL mode. | SYS-027 (new) | ⏳ Pending | ✅ | — |
| T4 | Update downstream docker-compose to use `ft-db`. Split into T4a–T4d (see below). | — | ⏳ Pending | ✅ | CRS jar/image built from `feature/ft-db-testing` |
| T5 | Extend `FtDbProfileTest` to cover more read endpoints (build-tools, find-by-artifact, VCS). | — | ⏳ Pending | ❌ | T1 result |

### T4 — downstream updates (one sub-agent per repo)

All four run in parallel. Downstreams consume CRS **built from this branch** — either the
locally built Docker image (`./gradlew :components-registry-service-server:dockerBuildImage`
on `feature/ft-db-testing`, tagged `ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT`)
or a jar published to a local Maven cache. No wait on PR #148 merge.

| ID | Downstream | Repo path | Status |
|----|------------|-----------|--------|
| T4a | DMS Service | `/Users/pgorbachev/projects/octopus/octopus-dms-service` (`server/dev/docker-compose.yml`) | ⏳ Pending |
| T4b | Releng | `/Users/pgorbachev/projects/ow/releng` (path TBD) | ⏳ Pending |
| T4c | Escrow Generator | `/Users/pgorbachev/projects/ow/escrow-generator` (path TBD) | ⏳ Pending |
| T4d | ORMS | TBD — not confirmed in scope | ⏳ Pending |

Each T4x sub-agent task:
1. Locate the CRS block in the downstream's compose file.
2. Set `SPRING_PROFILES_ACTIVE=common,ft-db` (replacing whatever uses PostgreSQL).
3. Remove the PostgreSQL sidecar if it exists only for CRS.
4. Run the downstream's FT suite locally — confirm registry boots and answers.
5. Open a PR in the downstream repo (do NOT merge).

## Requirements to be added

Not yet committed — will be added when T1 / T3 start.

### SYS-026 — Flyway-managed PostgreSQL schema passes Hibernate validate

**Layer:** integration-test
**Description:** Starting the server on PostgreSQL with Flyway V1–V3 applied and
`spring.jpa.hibernate.ddl-auto=validate` succeeds. Guards against silent dialect/DDL drift
when entity column definitions are loosened (e.g. removing PG-specific `columnDefinition`).

### SYS-027 — ft-db profile supports write operations

**Layer:** integration-test
**Description:** Under the `ft-db` profile (H2 in-memory, PostgreSQL compatibility mode),
POST creates a component and PATCH updates nested fields whose entity columns use
`columnDefinition = "jsonb"`.

## CI status reference

PR #148: CodeRabbit ✅ / GitGuardian ✅. The three "failed" entries in
`gh run list` are workflow-parse failures from the external
`octopus-base@feature/common-quality-gates` reference — they never executed jobs and appear
on every branch; not a regression.
