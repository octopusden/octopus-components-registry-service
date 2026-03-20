# ADR-001: PostgreSQL as Primary Storage

## Status
Accepted

## Context

The Components Registry Service needs to migrate from Git-based storage (Groovy/Kotlin DSL files) to a relational database. We need a database that supports:
- Complex relational data (components → versions → build configs → tools)
- Flexible schema for extensible fields (tool properties, doc links)
- Audit capabilities (change tracking with JSON diffs)
- Row-level security for future RBAC needs
- Strong Spring Boot ecosystem support
- Production-proven, open-source, free

## Considered Options

### Option A: PostgreSQL
- JSONB for flexible fields (tools `extra`, `doc`, labels)
- Native audit trigger support + temporal tables
- Row-level security built-in
- Best Spring Data JPA support
- Already used in `octopus-dms-service` (team expertise exists)
- Flyway migration support, Testcontainers available

### Option B: MySQL
- Widely used, good Spring Boot support
- Limited JSON support (no JSONB indexing equivalent)
- No native row-level security
- No temporal tables
- Flyway support available

### Option C: Supabase (PostgreSQL-as-a-Service + built-in API)
- PostgreSQL under the hood — same JSONB, relational integrity, Flyway compatibility
- Built-in REST API (PostgREST) and real-time subscriptions out of the box
- Auth, row-level security, storage — batteries included
- **Problem: no deployed instance** — the organization does not have a Supabase instance; DevOps do not officially support or maintain it
- Would require either self-hosting (operational overhead, no team expertise) or using Supabase Cloud (external dependency, data residency concerns)
- Adds a platform-level dependency for what is essentially a PostgreSQL database with an API layer we can build ourselves

### Option D: MongoDB
- Native JSON storage, flexible schema
- No relational integrity (foreign keys)
- Different Spring Data module (Spring Data MongoDB)
- Harder to enforce consistency for complex relational model
- Less alignment with existing octopus infrastructure (DMS uses PostgreSQL)

## Decision

**PostgreSQL 16+** as the primary storage engine.

## Consequences

### Positive
- Consistent with octopus ecosystem (DMS uses PostgreSQL)
- Team already has PostgreSQL expertise
- JSONB enables flexible fields without schema explosion
- Strong tooling: Flyway, Testcontainers, pgAdmin
- Row-level security available for future fine-grained access control

### Negative
- Requires PostgreSQL instance provisioning and maintenance
- Additional infrastructure dependency (service was previously stateless)

### Risks
- Database becomes single point of failure → mitigate with replication/backups
- Schema migrations need careful management → Flyway with review process

## References
- [octopus-dms-service PostgreSQL integration](https://github.com/octopusden/octopus-dms-service)
- [PostgreSQL JSONB documentation](https://www.postgresql.org/docs/current/datatype-json.html)
