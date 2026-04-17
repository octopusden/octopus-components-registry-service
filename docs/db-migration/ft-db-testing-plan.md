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
| **T1** | Added `FlywayValidatePostgresStartupTest` — PostgreSQL testcontainer + Flyway V1–V4 + `ddl-auto=validate`. Addresses CodeRabbit P1 concern: Hibernate's dialect-derived DDL for `system` without `columnDefinition` DOES resolve to `text[]` on PostgreSQL dialect — **P1 not reproduced**. Test kept as regression coverage. | SYS-026 | ✅ Done | ✅ | — |
| **T2** | Documented `ft-db` profile in `docs/db-migration/deployment/dev-run.md` (new "FT DB Mode" section). | — | ✅ Done | ✅ | — |
| **T3** | Add H2 write-path sanity test — POST/PATCH against ft-db profile. Uncovered real bug: Hibernate 6.4.1 `JacksonJsonFormatMapper` did unsafe `(String)value` cast for `Any?`-typed `@JdbcTypeCode(SqlTypes.JSON)` fields holding a Map. Not H2-specific (PG affected too). Fix: `SafeJsonFormatMapper` routes non-String Any values via Jackson. | SYS-027 | ✅ Done | ✅ | — |
| T4 | Update downstream docker-compose / OKD / Maven-docker-plugin configs to use `ft-db`. Split into T4a–T4d (see below). | — | 🔄 T4a done | ✅ | CRS jar/image built from `feature/ft-db-testing` (local for now; see T6) |
| T5 | Extend `FtDbProfileTest` to cover more read endpoints (build-tools, find-by-artifact, VCS). | — | ⏳ Pending | ❌ | T1 result |
| **T6** | **Branch-snapshot publishing** for downstream FT integration on TeamCity. CRS Docker image from `feature/ft-db-testing` should be pushed to a shared registry (e.g. `ghcr.io/octopusden/components-registry-service:pr-148` or `:branch-ft-db-testing`) so downstream FT can pull a reproducible artifact instead of relying on a local build. Raised by CodeRabbit P2. Required for cross-repo branch-based validation on TC. | — | ⏳ Pending | ✅ | — |

### T4 — downstream updates (one sub-agent per repo)

All four run in parallel. Downstreams consume CRS **built from this branch** — either the
locally built Docker image (`./gradlew :components-registry-service-server:dockerBuildImage`
on `feature/ft-db-testing`, tagged `ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT`)
or a jar published to a local Maven cache. No wait on PR #148 merge.

| ID | Downstream | Runner | CRS config location | Status |
|----|------------|--------|---------------------|--------|
| T4a | DMS Service | OKD (oc-template) | `octopus-dms-service/okd/components-registry.yaml` — profile `dev` (not `ft`) | ✅ PR [#83](https://github.com/octopusden/octopus-dms-service/pull/83) |
| T4b | Releng (jira-releng-plugin-ft) | fabric8 docker-maven-plugin | `ow/releng/ft/jira-releng-plugin-ft/pom.xml` | ⏳ Pending |
| T4c | Releng (maven-crm-plugin-ft) | fabric8 docker-maven-plugin | `ow/releng/ft/maven-crm-plugin-ft/pom.xml` | ⏳ Pending |
| T4d | ORMS (release-management-service) | docker-compose | `octopus-release-management-service/ft/docker/docker-compose.yml` + `components-registry-service.yaml` | ⏳ Pending |

Current state (both DMS and Releng): `SPRING_PROFILES_ACTIVE=ft`, mounts a custom
`application-ft.yaml` that disables VCS and points to a volume-mounted `/components-registry`
DSL tree, with CRS image at version `2.0.78` (pre-v3).

Each T4x sub-agent task:
1. Build CRS locally from `feature/ft-db-testing` → tag `ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT-ft-db`.
2. Switch the downstream's CRS block to that tag + `SPRING_PROFILES_ACTIVE=ft,ft-db` — keep the existing `ft` profile so the mounted `application-ft.yaml` still applies; add `ft-db` for H2 + auto-migrate. (`common` is a test-only profile in CRS, not in the runtime image.)
3. Keep the `/components-registry` DSL mount (auto-migrate reads from it at startup).
4. Reconcile settings from the old `application-ft.yaml` (product-type, supportedGroupIds,
   version-name, vcs-enabled=false) — either keep the override file or fold needed keys into
   a new environment block; decide per downstream.
5. Run the downstream's FT suite locally — confirm registry boots (H2 in-memory + auto-migrate)
   and downstream tests pass end-to-end.
6. Open a PR in the downstream repo (do NOT merge). If something breaks in CRS, stop and
   file it back to CRS plan per the TDD rule (requirement → red test → fix).

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

PR #148: CodeRabbit ✅ / GitGuardian ✅. The three "failed" entries (`build.yml`,
`quality-gates.yml`, `security.yml`) are **pre-existing** on base branch `v3` — the same
three workflows are red on every v3 commit since `dc9ac3d`. Root cause: all three reference
`octopusden/octopus-base/.github/workflows/*.yml@feature/common-quality-gates`, but that
branch does not exist in `octopus-base`. The workflows fail at resolution (0 jobs, 0s
duration) before any code runs. Not a regression introduced by PR #148.

**Fix track (out of scope for #148):** either create/restore
`octopusden/octopus-base@feature/common-quality-gates`, or update the three workflow files
on `v3` to pin to an existing ref (e.g. `@main` or `@v2.1.10`). Needs owner confirmation
of which common-workflow is the intended successor.
