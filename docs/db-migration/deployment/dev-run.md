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

## UI

After server is running: http://localhost:4567/ui
