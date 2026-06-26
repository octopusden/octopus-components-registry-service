# Production migration runbook

Step-by-step ops playbook for bringing a **fresh production environment** onto the DB-backed v3
service. This is the reusable, generic form of the cutover — another organization should be able to
follow it end to end. It is the production sibling of
[`qa-post-deploy-migration.md`](qa-post-deploy-migration.md) (what the QA `[3.1]` step automates) and
implements the strategy in [ADR-013](../adr/013-cutover-strategy.md).

> Conventions: replace every `<placeholder>` with your environment's value. Commands that mutate
> production (deploy, `vault`/secret writes, `pg_*`) should be reviewed and run by an operator.

## Strategy in one line

Deploy v3 in **git-mode first** (byte-identical to the legacy service) with the production database
**wired but not yet authoritative**, let Flyway create the schema, run the migration to populate the
database, verify, then **flip the resolver to `db`**. Every step is reversible.

## Prerequisites

- **Production PostgreSQL provisioned and EMPTY.** A `components-registry` database the service can
  reach. It must be empty on first deploy — see [Schema](#schema-flyway--validate).
- **Secrets/config in place** (see [Required configuration](#required-configuration)).
- **DSL repository reachable** — in git-mode the service clones the component DSL on startup
  (`components-registry.vcs.root` must be set), so the legacy DSL repo must be clone-able from the pod.
- **Admin credentials** — a principal/token holding `IMPORT_DATA` (the migrate endpoints are
  `@PreAuthorize("@permissionEvaluator.canImport()")`).
- **A staging rehearsal has already passed** — the *complete* migration has been run against a fresh
  staging Postgres using production DSL+config and finished with `failed == 0`. Do not first discover
  collisions in production.

## Required configuration

Set in the service config for the production profile (do **not** hard-code secrets; pull DB
credentials from your secret store):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<prod-pg-host>:5432/components-registry
    # username/password from the secret store
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate          # never 'create'/'update' in prod
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true                 # V1__schema.sql + V2 are authoritative
    # do NOT set baseline-on-migrate in prod (see Schema below)
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=300s

components-registry:
  database:
    enabled: true                 # wire JDBC/JPA/Flyway/migration beans
  default-source: git             # PHASE 1 — serve from Git. Flip to 'db' at the end.
  auto-migrate: false             # migration is operator-triggered, not on-boot
  vcs:
    root: <dsl-repo-url>          # required in git-mode (startup clone)
```

Required environment/secrets (the pod fails to start without the mandatory ones):

| Variable | Required | Purpose |
| --- | --- | --- |
| DB url + credentials | yes | Production PostgreSQL |
| `AUTH_SERVER_URL`, `AUTH_SERVER_REALM` | yes | Keycloak (OIDC discovery is eager — missing values fail boot) |
| Employee-service URL/token | only if employee validation enabled | Active-employee checks |

### Schema (Flyway + validate)

- Flyway runs **first** at startup and applies `db/migration/V1__schema.sql` + `V2__…` to the empty
  database; Hibernate `ddl-auto: validate` then checks the resulting schema against the entity model
  and **fails boot on any mismatch** (the desired safety property).
- **Do not set `flyway.baseline-on-migrate: true` in production.** On a non-empty schema Flyway would
  *baseline* it and skip `V1`, leaving an invalid schema that `validate` may not catch. The production
  database must be empty at first deploy. If Flyway finds an unexpectedly non-empty schema, **stop and
  investigate** rather than baseline.
- Pre-flight (CI): the `:components-registry-service-server:integrationTest` task includes
  `FlywayValidatePostgresStartupTest`, which proves Flyway + `validate` boot cleanly against a fresh
  Postgres. Confirm it is green before deploying.

## Procedure

1. **Deploy v3 in git-mode.** With the config above (`default-source: git`, DB wired), deploy the
   release image. The pod boots serving from Git (byte-identical to the legacy service), Flyway
   applies the schema to the empty DB, and `validate` passes. Watch the pod come ready.
2. **Verify git-mode parity.** Spot-check a few reads (e.g. `GET /rest/api/3/components/...`) against
   known-good output before touching the database.
3. **Back up first.** `pg_dump components-registry > pre-migrate-<timestamp>.dump`. This is the restore
   point for the recovery path below.
4. **Populate the database.** `POST /rest/api/4/admin/migrate` (async) returns **202** on a freshly
   started job and **409** if a migration is already running (the 409 body carries the in-flight job —
   poll that one rather than re-POSTing). Poll `GET /rest/api/4/admin/migrate/job` until terminal.
   Optionally `POST /rest/api/4/admin/migrate-history` for audit backfill. Both require `IMPORT_DATA`.
5. **Check the result.** The job must end with `failed == 0`. If `failed > 0` (or the pod crashed
   mid-run), follow [Recovery](#recovery-on-partial-or-failed-migration) — do **not** proceed.
6. **Verify completeness.** `GET /rest/api/4/admin/migration-status` (admin) reports the source
   counters — confirm 100% `db`-sourced and that counts match expectations.
7. **Flip to db-mode.** Set `components-registry.default-source: db`, redeploy/restart. The service now
   resolves from the database.

> Two distinct status endpoints: anonymous `GET /rest/api/4/migration-status` reports the running/idle
> job **state** (poll it for "is a migration in progress"); admin `GET /rest/api/4/admin/migration-status`
> returns the source **counters** (use it to confirm 100% db-sourced).

## Migration semantics

- **Not atomic across the batch.** Defaults and components are migrated in separate transactions and
  per-component failures are caught, so successful components commit **even when `failed > 0`** — a
  crashed/restarted pod can leave a partially-populated database.
- **Idempotent re-run does not repair a partial DB.** A component can partially write before a caught
  exception; a later run sees the existing `componentKey` and skips it as already-in-DB, leaving the
  partial rows in place. Only a run against a **clean** database is trustworthy.
- **Job state is single-pod / in-memory.** A pod rollout makes `migrate/job` return 404 on the new pod
  — do not roll or restart the pod mid-migration.
- **Collision detection needs a real run.** `dryRun=true` skips uniqueness validation and
  `migration-status` only counts sources — neither detects global collisions (displayName / GAV / Jira
  / Docker). The pre-flight staging rehearsal with production DSL is what surfaces them. **Note:**
  artifact-id ownership overlaps (`component_artifact_ids`) are covered **only after the #357
  validation lands**; until then the migration pre-pass does not check them, so the rehearsal does not
  gate that case.

## Recovery (on partial or failed migration)

If the migrate job ends with `failed > 0`, or the pod crashed mid-migration:

1. **Do not flip to db-mode.** Stay in git-mode (the service keeps serving from Git, unaffected).
2. Stop the service.
3. Restore the pre-migration dump, or drop & recreate the schema so Flyway rebuilds it clean.
4. Restart the service in **git-mode**.
5. Fix the root cause (inspect the failing component in the job artifact / pod logs).
6. Re-run the migration against the now-clean database. Only then consider the flip.

## Rollback

- **While still in git-mode:** redeploy the prior image / keep `default-source: git` — the Git
  resolver is byte-identical and the database is untouched.
- **After the db flip:** revert `default-source: git` and restart (the database is retained), or
  redeploy the previous released image. The previous immutable image/tag is the runtime fallback.
- Database backups (including the pre-migration dump) are the data floor.

## After cutover

Git-resolver removal and the eventual drop of the routing layer are **post-cutover** work with their
own bake-in windows — see [ADR-013](../adr/013-cutover-strategy.md). Do not remove the Git path as
part of this runbook.

## Source references

- Admin endpoints: `AdminControllerV4` (`migrate`, `migrate/job`, `migrate-history`,
  `migration-status`) — `@PreAuthorize("@permissionEvaluator.canImport()")`.
- Anonymous probe: `MigrationStatusControllerV4` (`GET /rest/api/4/migration-status`).
- Resolver source flag: `ComponentsRegistryProperties.defaultSource` (`git`|`db`).
- Schema: `db/migration/V1__schema.sql`, `V2__add_distribution_docker_image_name_index.sql`.
- QA automation of the same migrate call: [`qa-post-deploy-migration.md`](qa-post-deploy-migration.md).
- Strategy & post-cutover stages: [ADR-013](../adr/013-cutover-strategy.md).
