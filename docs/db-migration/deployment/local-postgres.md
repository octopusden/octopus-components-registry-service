# Local PostgreSQL for Demo Development

## Goal

Run a local PostgreSQL instance for the future DB-backed Components Registry demo without requiring a
native database installation on the developer laptop.

This setup is intentionally optimized for:

- local development
- repeated demo resets
- simple onboarding on a laptop with Docker/Colima

It is not intended to be production-like in every detail.

## Chosen Approach

- PostgreSQL runs in Docker via `docker-compose.local-postgres.yml`
- data is stored in a named Docker volume: `crs_postgres_data`
- persistence is enabled by default
- full reset is explicit and easy: remove the volume and re-import data

## Why This Approach

- simpler than installing PostgreSQL locally
- close enough to the target runtime to validate DB integration
- easy to preserve state across restarts
- easy to wipe and recreate for demo scenarios

## Files

- `docker-compose.local-postgres.yml`
- `.env.local-postgres.example`

## First-Time Setup

1. Copy `.env.local-postgres.example` to `.env.local-postgres`.
2. Adjust credentials or port only if `5432` is already occupied.
3. Start PostgreSQL:

```bash
docker-compose --env-file .env.local-postgres -f docker-compose.local-postgres.yml up -d
```

4. Verify the container is healthy:

```bash
docker-compose --env-file .env.local-postgres -f docker-compose.local-postgres.yml ps
```

## Connection Values

Default values:

- host: `localhost`
- port: `5432`
- database: `components_registry`
- user: `crs`
- password: `crs`

JDBC URL:

```text
jdbc:postgresql://localhost:5432/components_registry
```

## Normal Daily Workflow

1. Start PostgreSQL:

```bash
docker-compose --env-file .env.local-postgres -f docker-compose.local-postgres.yml up -d
```

2. Start the backend once DB support is implemented.
3. Let the backend apply migrations on startup.
4. Run the future import step to populate the DB from the current Components Registry source.
5. Start the UI against the local backend.

## Reset Workflow for Demo

If the team wants a clean demo state, it is acceptable to recreate the database and re-import the
registry from scratch.

Stop and delete the DB plus volume:

```bash
docker-compose --env-file .env.local-postgres -f docker-compose.local-postgres.yml down -v
```

Then start it again:

```bash
docker-compose --env-file .env.local-postgres -f docker-compose.local-postgres.yml up -d
```

After that, re-run migrations and the future import flow.

## Persistence Policy

For the current demo phase, persistence is useful but not critical.

Recommended behavior:

- keep the named volume in normal work so restarts are cheap
- use `down -v` only when a clean state is needed
- assume re-importing the registry after a full reset is acceptable

## Planned Integration Points

When DB support is implemented, the local developer setup should add:

- Spring datasource properties pointing to this local PostgreSQL
- Flyway migrations on application startup
- a documented import/bootstrap command for loading registry data
- optional sample data or a deterministic seed path for demos

## Notes

- This file intentionally does not claim that the current application already supports PostgreSQL.
- It prepares the local infrastructure so the DB-backed path can be developed and demonstrated as
  soon as the server-side implementation lands.
