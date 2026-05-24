# QA post-deploy migration

After every successful QA-DEV deploy (`[3.0] Deploy to OKD QA DEV [AUTO]`,
`id30DeployToOkdQaDevAuto`), TeamCity runs `[3.1] Migrate components on QA DEV
[AUTO]` (`id31MigrateOnQaDevAuto`). The step calls
`POST /rest/api/4/admin/migrate` on the freshly-deployed pod and polls
`GET /rest/api/4/admin/migrate/job` until the migration reaches a terminal
state.

## When the step skips (exit 0)

Tolerant by design — silently skips when migration is not applicable:

- `GET /rest/api/4/admin/migration-status` returns **HTTP 404** → the deployed
  build pre-dates the v3 admin API, nothing to do.
- `migration-status` reports `git == 0` → everything is already in DB, the
  redeploy did not introduce new Git-sourced components.

## When the step fails

- Pod is not ready within `READINESS_TIMEOUT_SEC` (default 300 s).
- `POST /admin/migrate` returns 401/403 → the Keycloak token in
  `CRS_QA_ADMIN_TOKEN` is missing, expired, or lacks the `IMPORT_DATA` role.
  Provision it once on the TC project parent (`CRS_QA_ADMIN_TOKEN` password
  parameter) and rerun.
- `POST /admin/migrate` returns 409 with an `activeKind` other than
  `COMPONENTS` → a history-migration or TC-resync is holding the
  lifecycle gate. Wait for that job to finish, then rerun `[3.1]`.
- Migration job ends in `state=FAILED` or `state=COMPLETED` with `failed > 0`
  → real migration breakage. Open the job artifact in TC and look at the last
  `Job: state=... migrated=... failed=...` line, then drill into the pod logs
  for the failing component.

## TC server-level parameters required

The build references two project-level parameters that **are not** committed
to the open-source DSL:

| Parameter             | Type     | Purpose                                              |
| --------------------- | -------- | ---------------------------------------------------- |
| `CRS_QA_DEV_BASE_URL` | text     | Base URL of the QA-DEV CRS route, e.g. `https://crs.qa-dev.example` |
| `CRS_QA_ADMIN_TOKEN`  | password | Bearer JWT (Keycloak service account, `IMPORT_DATA`) |

The build will fail loudly until both are populated. See
`keycloak-setup.md` for the role-mapping side.

## Re-running manually

The step is idempotent. Hitting "Run" on `id31MigrateOnQaDevAuto` from the TC
UI re-checks `migration-status` and migrates whatever delta is left. A second
run against a clean stand exits 0 on the `git == 0` short-circuit.

If the build appears to "hang", the script is still inside the polling loop —
job progress is logged every poll interval (10 s by default). The 45-minute
overall timeout is set in `failureConditions`.

## Relation to `COMPONENTS_REGISTRY_AUTO_MIGRATE`

The codebase also supports an in-pod auto-migrate-on-startup mode triggered by
the env var `COMPONENTS_REGISTRY_AUTO_MIGRATE=true`
(`ComponentsRegistryServiceImpl`, `@PostConstruct cloneVcsData`). That mode
runs migration synchronously during boot and refuses to start the pod on
partial failure — strict but blocking.

**Use one or the other for a given environment**, not both. On QA we prefer
the post-deploy TC step because it:

- decouples migration failure from the deploy chain (the pod can serve
  read traffic while the migration is investigated),
- tolerates "nothing to migrate" without restarting the pod,
- surfaces migration progress in TC, not buried in pod logs.

If you ever flip QA to `COMPONENTS_REGISTRY_AUTO_MIGRATE=true`, disable the
`finishBuildTrigger` on `id31MigrateOnQaDevAuto` (or remove it) — running both
just means the TC step always sees `git == 0` and exits.

## Source

- TC build: `.teamcity/settings.kts` → `object id31MigrateOnQaDevAuto`
- Script: `scripts/teamcity/qa-post-deploy-migrate.sh`
- Admin endpoint: `AdminControllerV4.migrate` (`@PreAuthorize canImport`)
- Auto-migrate path: `ComponentsRegistryServiceImpl.cloneVcsData`
