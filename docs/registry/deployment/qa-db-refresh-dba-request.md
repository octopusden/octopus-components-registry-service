# DBA request: database access for the QA refresh build

Access needed by the TeamCity build `[8.0] Recreate QA DB from PROD [MANUAL]`
([qa-db-refresh.md](qa-db-refresh.md)). The build copies the `components-registry` schema from the
production CRS database to the QA one with `pg_dump` / `psql`. On production it only reads; it must be
unable to write there, and unable to hold locks that stall the application.

## 1. Production: a read-only role

Server `prod-components-registry-pg`, database `components-registry`.

| Parameter | Value |
|---|---|
| Name | `components-registry-readonly` |
| Attributes | `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT` |
| Privileges | membership in `pg_read_all_data` only, **without** `ADMIN OPTION`; no other grants, owns nothing |
| Session default | `default_transaction_read_only = on` |
| Connection limit | 3 |
| Password | generated, at least 32 characters, stored as SCRAM-SHA-256 |
| Database access | `CONNECT` on `components-registry` (normally already granted to `PUBLIC`) |

```sql
CREATE ROLE "components-registry-readonly" LOGIN PASSWORD '<generated>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT pg_read_all_data TO "components-registry-readonly";
ALTER ROLE "components-registry-readonly" SET default_transaction_read_only = on;
```

Why `pg_read_all_data` rather than per-table grants: it also covers tables that future application
migrations create. Explicit grants would need default privileges kept in step with the migrations, or
the dump starts failing after the next one.

## 2. Network and `pg_hba`

| Server | Database | Role | From |
|---|---|---|---|
| `prod-components-registry-pg` | `components-registry` | `components-registry-readonly` | TeamCity agents; the internal/VPN network |
| `qa-lifecycle-svc-pg` | `components-registry` | `components-registry` (the existing QA application user) | TeamCity agents |

Port 5432 from the TeamCity agents to both servers. Nothing else changes on QA: the application user
already owns the schema and its extension and may `CREATE` in the database, which is all the copy needs.
The copy touches only the `components-registry` database, not `lifecycle-svc` on the same server.

## 3. Handing over the password

Write it directly into Vault, not by mail or chat:

- path `f1-config-server/teamcity-crs-qa-refresh` (KV v2)
- `prod.readonly.username` = `components-registry-readonly`
- `prod.readonly.password` = the password

The approle of the TeamCity Vault connection ("HashiCorp Vault Cloud Wrapper") needs `read` on:

```hcl
path "f1-config-server/data/teamcity-crs-qa-refresh"           { capabilities = ["read"] }
path "f1-config-server/data/components-registry-service-cloud-qa" { capabilities = ["read"] }
```

## 4. Acceptance

Connected as `components-registry-readonly` to production:

```sql
SELECT pg_has_role(current_user, 'pg_read_all_data', 'member');   -- true
SELECT current_setting('default_transaction_read_only');           -- on
SELECT count(*) FROM "components-registry".components;              -- returns a count
SELECT system_identifier FROM pg_control_system();                  -- returns a value
CREATE TABLE "components-registry".x (i int);                       -- must fail
SET default_transaction_read_only = off;
CREATE TABLE "components-registry".x (i int);                       -- must still fail (no privilege)
```

Then one run of the TeamCity build confirms the network paths and the Vault references.
