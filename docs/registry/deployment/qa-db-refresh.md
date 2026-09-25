# Recreate the QA database from production

TeamCity build `[8.0] Recreate QA DB from PROD [MANUAL]` replaces the `components-registry` schema of the
QA database with a copy of the production one. Script: `scripts/teamcity/recreate-qa-db-from-prod.sh`.

## What a run does

1. Checks that the target is not production (below). Nothing is written before these checks pass.
2. Dumps the production schema, including the extensions installed in it (`pgcrypto`).
3. Keeps the current QA schema as the build artifact `qa-backup/qa-before-recreate.sql.gz`.
4. In one transaction on QA: `DROP SCHEMA "components-registry" CASCADE`, then loads the dump. Any failure,
   including a 30 s lock timeout while a QA session holds a table, rolls back and leaves QA as it was.
5. Compares row counts table by table (a difference is a warning: production keeps serving writes) and
   logs the production migration version now on QA.

The data is copied as is. Nothing outside the `components-registry` schema is copied or changed, except
objects elsewhere that depend on it (a view in `public` over its tables, say), which `CASCADE` drops.

## What it does not do

The QA application is **not** restarted and no migrations are run. After a run, QA pods keep running
their own version against the production schema. Component data is read from the database on every
request, so it is current at once, but:

- `registry_config` (field-config, component-defaults) now holds the production copy. QA writes its
  own service-config values there only on startup or on `POST /admin/reload-config`;
- if QA runs a newer version than production (the usual case), endpoints that need the newer schema
  fail until QA is redeployed; the application's Flyway brings the schema forward on startup;
- if production is ahead of the QA version, the next QA startup fails Flyway validation until a version
  that knows the production migrations is deployed.

Whoever presses the button redeploys the QA version they need.

## Why production is safe

- The production credential is a role holding only `pg_read_all_data`. Every production session is also
  opened with `default_transaction_read_only=on`, and only `pg_dump` and `SELECT` queries receive it.
- `pg_dump` takes `ACCESS SHARE` locks, which conflict only with DDL, and gives up after 30 s instead of
  queueing. A production migration that starts during the dump waits for it (seconds at the current
  size), and queries on that table queue behind the migration meanwhile.
- Before writing, the script refuses unless the target has a different host name from the source, does
  not mention `prod` in its host name, and is a different PostgreSQL cluster (`system_identifier` from
  `pg_control_system()`, which catches the same server reached under another name).
- The build runs only on the default branch, so a custom run cannot execute an unreviewed script with the
  production credential.
- The connection parameters are read-only in TeamCity. The parent-project parameters they reference
  (`CRS_*_DB_HOST`, `DOCKER_REGISTRY`) can still be overridden in a custom run: a redirected target is
  stopped by the cluster-identity check, and a leaked production credential can only read — the same
  data QA holds after a run. Marking the two host parameters read-only on the parent project closes
  the redirection for them.

`scripts/teamcity/test/test-recreate-qa-db-from-prod.sh` checks all of this against two local containers
(needs Docker, touches no real database).

## One-time setup

1. **Read-only role on production**, run by a database administrator:

   ```sql
   CREATE ROLE crs_prod_readonly LOGIN PASSWORD '<generated>';
   GRANT pg_read_all_data TO crs_prod_readonly;
   ```

2. **Vault** (KV mount `f1-config-server`):
   - new secret `teamcity-crs-qa-refresh` with keys `prod.readonly.username` and `prod.readonly.password`.
     The name deliberately does not start with `components-registry-service`, so the config server
     serves it to no application;
   - the QA credentials are read from the QA application's own secret
     `components-registry-service-cloud-qa` (`spring.datasource.username` / `spring.datasource.password`);
   - the approle of the TeamCity Vault connection needs `read` on both paths. If the mount is KV v2,
     the policy paths and the `%vault:...%` references in `.teamcity/settings.kts` need `/data/` after
     the mount name. The first run confirms the references, including keys that contain dots.

3. **TeamCity parameters** on the parent project: `CRS_PROD_DB_HOST` and `CRS_QA_DB_HOST`, preferably
   with the read-only spec.

4. **QA privileges**: the QA application user must own the schema and `pgcrypto` and have `CREATE` on
   the database. This holds today, because the application created them.

## Undoing a run

Download `qa-before-recreate.sql.gz` from the build and load it the same way the script does. The dump
starts with a `\restrict` meta-command, so it needs psql 17.6 or newer (or the `postgres:17` image):

```bash
{ echo 'DROP SCHEMA IF EXISTS "components-registry" CASCADE;'; gunzip -c qa-before-recreate.sql.gz; } |
  psql -h <qa-host> -U <qa-user> -d components-registry -X -v ON_ERROR_STOP=1 --single-transaction
```
