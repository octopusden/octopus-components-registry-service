# Local Dev Run — DB Mode

## Prerequisite

PostgreSQL running:
```bash
docker compose -f docker-compose.local-postgres.yml up -d
```

## Start Server

```bash
./gradlew :components-registry-service-server:bootRun \
  --args="--spring.profiles.active=dev,dev-vcs-local,dev-db \
  --spring.config.additional-location=file:components-registry-service-server/dev/ \
  --components-registry.vcs.root=file:///Users/pgorbachev/projects/ow/components-registry"
```

Profiles:
- `dev` — base dev config (port 4567, no eureka, supportedGroupIds/Systems)
- `dev-vcs-local` — clone VCS root to `/tmp/components-registry`, use real DSL
- `dev-db` — PostgreSQL datasource + Flyway + JPA

On first start it clones the registry (~40s), then loads all DSL files.

## Migration Workflow (repeat as needed during debugging)

### 1. Migrate all components (Git → DB)

```bash
curl -s -X POST http://localhost:4567/rest/api/4/admin/migrate-components
```

Or single component:
```bash
curl -s -X POST http://localhost:4567/rest/api/4/admin/migrate-component/COMPONENT_NAME
```

Dry run (no writes):
```bash
curl -s -X POST "http://localhost:4567/rest/api/4/admin/migrate-component/COMPONENT_NAME?dryRun=true"
```

### 2. Migrate Defaults.groovy → component-defaults config

```bash
curl -s -X POST http://localhost:4567/rest/api/4/admin/migrate-defaults
```

### 3. Check migration status

```bash
curl -s http://localhost:4567/rest/api/4/admin/migration-status
```

Expected after full migration: `{"git":0,"db":933,"total":933}`

### 4. Validate a component

```bash
curl -s -X POST http://localhost:4567/rest/api/4/admin/validate-migration/COMPONENT_NAME
```

## Reset DB and Re-migrate

```bash
docker compose -f docker-compose.local-postgres.yml down -v
docker compose -f docker-compose.local-postgres.yml up -d
```

Then restart server — Flyway applies migrations automatically.
Then repeat migration workflow above.

## Auto-Migrate Mode

Starts server and automatically migrates all components to DB (no manual curl needed).
Use profile `dev-db-automigrate` instead of `dev-db`:

```bash
./gradlew :components-registry-service-server:bootRun \
  --args="--spring.profiles.active=dev,dev-vcs-local,dev-db-automigrate \
  --spring.config.additional-location=file:components-registry-service-server/dev/ \
  --components-registry.vcs.root=file:///Users/pgorbachev/projects/ow/components-registry"
```

This is the profile used by downstream projects (DMS Service, Releng, Escrow Generator) in their Docker Compose setups.

## FT DB Mode (H2 in-memory)

Self-contained CRS backed by H2 — no PostgreSQL, no Flyway, no external deps. Intended
for downstream FT suites (DMS, ORMS, Releng) that need a read-only registry spun up fast.

- **H2 in-memory** datasource (`jdbc:h2:mem:crs;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE`)
- **Flyway disabled**; Hibernate `ddl-auto=create-drop` creates the schema on boot
- **Auto-migrate on startup** (`components-registry.auto-migrate=true`) — reads Git DSL
  from `components-registry.vcs.root`, loads components + defaults into H2, then serves
  reads from the DB
- Source: `application-ft-db.yml` (main config) + `bootstrap-ft-db.yml` (disables Spring
  Cloud Config)

Activate with `SPRING_PROFILES_ACTIVE=common,ft-db`. The `common` base profile is
**required** — it supplies `eureka.client.enabled=false`, `supportedGroupIds`, version-name
mapping, etc. `ft-db` alone will not boot.

The DSL tree must still be mounted where `components-registry.work-dir` points (default
`/components-registry`) — auto-migrate reads DSL files from there at startup.

**Warning:** all data lives in RAM. Any POST/PATCH via the API disappears on restart.
Use `ft-db` for read-only FT consumption, not for development.

### Downstream Docker Compose example

```yaml
components-registry-service:
  image: ghcr.io/octopusden/components-registry-service:1.0-SNAPSHOT-ft-db
  ports:
    - "4567:4567"
  environment:
    SPRING_PROFILES_ACTIVE: common,ft-db
    SPRING_CONFIG_ADDITIONAL_LOCATION: /
    SPRING_CLOUD_CONFIG_ENABLED: "false"
    PRODUCT_TYPE_C: PT_C
    PRODUCT_TYPE_K: PT_K
    PRODUCT_TYPE_D: PT_D
    PRODUCT_TYPE_DDB: PT_D_DB
  volumes:
    - ./components-registry:/components-registry:ro
    - ./application-dev.yaml:/application-dev.yaml:ro
```

`application-dev.yaml` keeps the downstream-specific overrides (work-dir, groovy-path,
supportedGroupIds, vcs.enabled=false). See `octopus-dms-service/test-common/src/main/config/components-registry-service.yaml`
for a concrete example.

## Product Type Configuration

`components-registry.product-type` values are environment-specific and **must not be committed**.
Create `components-registry-service-server/dev/application-local.yml` (gitignored):

See `application-local.yml.example` for the template and allowed values.
Without this file, server startup will fail with `Required key 'components-registry.product-type.c' not found`.

For downstream Docker Compose, pass via environment variables:
`PRODUCT_TYPE_C`, `PRODUCT_TYPE_K`, `PRODUCT_TYPE_D`, `PRODUCT_TYPE_DDB`.

## UI

UI has been extracted to a separate repository: `octopus-components-management-portal`.
See that repo's README for instructions on running the management portal.
