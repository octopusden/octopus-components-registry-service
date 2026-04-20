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
| T4 | Update downstream docker-compose / OKD / Maven-docker-plugin configs to use `ft-db`. Split into T4a–T4d (see below). | — | 🔄 T4a done | ✅ | TC-published branch snapshot `2.0.84-3122` (see T6) |
| T5 | Extend `FtDbProfileTest` to cover more read endpoints (build-tools, find-by-artifact, VCS). | — | ⏳ Pending | ❌ | T1 result |
| **T6** | Branch-snapshot publishing — already in place. TeamCity publishes branch builds as `2.0.84-3122` (snapshot of `feature/ft-db-testing`). Downstream FT runs should use this tag via `OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION=2.0.84-3122`. **Do not commit this value** in downstream repos — it is a branch snapshot, not a release. Closes CodeRabbit P2. | — | ✅ Done | — | — |

### T4 — downstream updates (one sub-agent per repo)

**Scope:** T4 PRs are **validation artifacts**, not merge candidates. The goal is to
confirm that the `ft-db` profile integrates correctly with each downstream's FT setup
— container starts, auto-migrate runs, endpoints return DB data. Continuous integration
testing (TC pipeline bumps, CI-level reproducibility) is **out of scope**. We do not plan
to merge T4a/T4b/T4c/T4d. Once integration is confirmed, the PRs can stay open for
reference or be closed without merging.

Because the PRs are throwaway validation, **committing the concrete CRS branch snapshot
version** (e.g. `2.0.84-3122`) in each downstream's version property is the preferred
approach — anyone can clone the branch and run the FT without remembering to set an env
var. The usual "don't commit snapshot versions" hygiene doesn't apply here since the PR
isn't going to main.

Caveat: some downstreams (ORMS) use one Gradle property for both the Docker image tag
and a Maven client dep. Committing the snapshot tag there breaks compile if the Maven
artifact isn't published for that snapshot. Verify per-downstream before committing.

| ID | Downstream | Runner | CRS config location | Status |
|----|------------|--------|---------------------|--------|
| T4a | DMS Service | OKD (oc-template) | `octopus-dms-service/okd/components-registry.yaml` — profile `dev` (not `ft`) | ✅ PR [#83](https://github.com/octopusden/octopus-dms-service/pull/83) |
| T4b+c | Releng (jira-releng-plugin-ft + maven-crm-plugin-ft) | fabric8 docker-maven-plugin | `ow/releng/ft/{jira-releng-plugin-ft,maven-crm-plugin-ft}/pom.xml` | ✅ branch `chore/crs-ft-db` pushed under Jira RELENG-3471. Commit includes profile edit + version bump + CRS JVM bump to 512m/256m metaspace (100m OOMed during Groovy DSL compile). Local FT run not completed on this machine. PR needs manual creation from the internal bitbucket URL. |
| T4d | ORMS (release-management-service) | docker-compose | `octopus-release-management-service/ft/docker/docker-compose.yml` + `components-registry-service.yaml` | ✅ PR [#59](https://github.com/octopusden/octopus-release-management-service/pull/59) |
| T4e | escrow-generator | OKD (oc-template via gradle-scripts) | `ow/escrow-generator/pom.xml` (client jars) + `gradle-scripts/gradle.properties` (`components-registry-service-server.version` — pod image tag) + `gradle-scripts/okd/components-registry-service.yaml` (`SPRING_PROFILES_ACTIVE=ft-db` env) | ✅ branch `ESCR-1666/crs-ft-db` pushed (internal bitbucket; PR needs manual creation) |
| T4f | octopus-rm-gradle-plugin | none (light-client jar only — compat check) | `octopus-rm-gradle-plugin/build.gradle` (light-client version bump) + `gradle.properties` (`use_dev_repository=dependencies`) | ✅ branch `chore/crs-ft-db` pushed; PR URL: https://github.com/octopusden/octopus-rm-gradle-plugin/pull/new/chore/crs-ft-db |

Current state per downstream:
- **DMS:** OKD deployment via `oc-template`; `SPRING_PROFILES_ACTIVE=dev`; mounts custom
  `application-dev.yaml`; CRS image at release `2.0.78`.
- **Releng (both FT modules):** fabric8 docker-maven-plugin; `SPRING_PROFILES_ACTIVE=ft`;
  mounts custom `application-ft.yml`; CRS image at release `2.0.78`.
- **ORMS:** docker-compose; `SPRING_PROFILES_ACTIVE=ft`; mounts custom
  `components-registry-service.yaml` as `application-ft.yaml`; committed CRS version
  `2.0.52`. Note: ORMS uses the same Gradle property
  `octopus-components-registry.version` for both the image tag AND the
  `components-registry-service-client` Maven dep — override only the Docker image via the
  `OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION` env var (documented in the PR test plan).

Each T4x sub-agent task:
1. In the downstream's CRS block, change `SPRING_PROFILES_ACTIVE=<existing>` to
   `<existing>,ft-db` — e.g. `dev,ft-db` for DMS, `ft,ft-db` for Releng and ORMS. The
   existing profile keeps Spring loading the downstream's mounted override file; `ft-db`
   layers H2 + auto-migrate on top. (`common` is a CRS test-only profile, not in the
   runtime image — do not use it.)
2. Do NOT change the committed CRS image version. At FT-run time override the version to
   TC branch snapshot `2.0.84-3122` via command-line property or env var — not in
   committed files.
3. Keep the existing `/components-registry` DSL mount (auto-migrate reads from it at
   startup) and the downstream's custom `application-<profile>.yaml` mount.
4. Run the downstream's FT suite locally with the override — confirm registry boots
   (H2 in-memory + auto-migrate) and downstream tests pass end-to-end.
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
