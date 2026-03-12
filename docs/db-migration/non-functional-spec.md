# Non-Functional Specification: Components Registry DB Migration

## Status
**Draft** | Date: 2026-03-08

---

## 1. Performance

| Metric | Target | Rationale |
|--------|--------|-----------|
| API response time (read, p95) | < 100ms | Current in-memory reads are ~5ms; DB + cache should stay fast |
| API response time (write, p95) | < 500ms | Includes validation, DB write, audit event |
| Component list (full, no filter) | < 200ms | Expected ~500-2000 components |
| Cache hit ratio | > 95% | Most reads are repeated (CI/CD pipelines) |
| Import time (full DSL → DB) | < 5 min | One-time operation, acceptable delay |
| Import validation overhead | < 50ms additional per component | Async comparison during per-component import, not on read path |

### Caching Strategy
- **Caffeine** in-process cache for hot read paths
- Cache invalidation on any write operation (v4 API)
- TTL: 5 minutes for list queries, 1 minute for individual component lookups
- Cache-aside pattern: check cache → miss → query DB → populate cache

## 2. Availability

| Metric | Target |
|--------|--------|
| Service uptime | 99.5% (allows ~3.6h downtime/month) |
| Database availability | PostgreSQL with replication (streaming replica) |
| Zero-downtime deployments | Rolling update via Kubernetes/OKD |
| Migration cutover | Zero-downtime via per-component source routing (`component_source` table) |

### Failure Modes
| Failure | Impact | Recovery |
|---------|--------|----------|
| PostgreSQL down | During migration: Git-sourced components OK, DB-sourced fail. After migration: service unavailable | Failover to replica; alert |
| Cache failure | Higher DB load, slower responses | Service continues without cache |
| Keycloak down | v4 write endpoints unavailable | v1/v2/v3 read endpoints still work (no auth required) |

## 3. Scalability

- **Components**: Support up to 10,000 components with 10 version ranges each
- **Audit log**: Support 1M+ entries (partitioning by `changed_at` month if needed)
- **Concurrent users (UI)**: Up to 50 simultaneous editors
- **API throughput**: 500 req/s read, 50 req/s write

## 4. Security

### 4.1 Authentication
- JWT tokens issued by Keycloak (RS256)
- Token validation via `spring-security-oauth2-resource-server`
- Token refresh: handled by Keycloak JS adapter (UI) / client credentials (service-to-service)

### 4.2 Authorization
- Role-based (RBAC) via `octopus-security-common`
- Permission checks via `@PreAuthorize` annotations
- No direct database credentials exposed to UI

### 4.3 Data Protection
- Database credentials stored in Vault (via Spring Cloud Config)
- No PII stored in components registry (component configs only)
- Audit log stores Keycloak username (not sensitive)
- HTTPS for all API traffic (terminated at API Gateway)

### 4.4 Input Validation
- **Backend**: Bean Validation (Jakarta `@Valid`, `@NotBlank`, `@Size`, `@Pattern`)
- **Frontend**: Zod schemas (client-side only, defense in depth)
- SQL injection: prevented by JPA parameterized queries
- XSS: prevented by JSON API (no HTML rendering on backend)

## 5. Reliability & Fitness Functions

Reliability is guaranteed through multiple layers of automated verification. Each layer has **fitness functions** — automated checks that run in CI or production to ensure architectural characteristics are maintained.

### 5.1 Data Correctness (Critical)

The #1 risk of migration: DB returns different data than Git.

| Fitness Function | Where | How |
|-----------------|-------|-----|
| Per-component equivalence check | Migration time | Deep-compare Git vs DB response for every migrated component |
| Existing test suite on DB resolver | CI | Run all 250+ .groovy test fixtures against `DatabaseComponentRegistryResolver` |
| Import validation mismatch rate = 0 | Production (during migration) | Prometheus alert if `registry.migration.validation.mismatch > 0` |
| Feign client contract tests | CI | All 28 `ComponentsRegistryServiceClient` methods return identical DTOs |
| Import idempotency | CI | Import same DSL twice → same DB state (no duplicates, no diffs) |

**Automated verification flow during migration:**
```
For each component:
  1. Import DSL → DB
  2. Read from Git resolver  → Response A
  3. Read from DB resolver   → Response B
  4. Deep equals (A, B)      → PASS: flip source to DB
                              → FAIL: log diff, keep on Git, alert
```

### 5.2 Contract Guarantees (API Backward Compatibility)

| Fitness Function | Where | How |
|-----------------|-------|-----|
| Feign client compilation | CI | Published `components-registry-service-client` compiles against server |
| Response schema stability | CI | Snapshot tests: v1/v2/v3 response JSON structure unchanged |
| No endpoint removal | CI | OpenAPI diff check: no paths/methods removed between versions |
| Consumer-driven contracts | CI | Spring Cloud Contract or Pact tests from consumer repos |
| HTTP status code stability | CI | Same inputs → same status codes (200, 404, 400) |

### 5.3 Architecture Fitness Functions (ArchUnit)

```java
// Layering: controllers never access repositories directly
@ArchTest
noClasses().that().resideInAPackage("..controller..")
    .should().accessClassesThat().resideInAPackage("..repository..")

// Security: all v4 endpoints require authorization
@ArchTest
methods().that().areDeclaredInClassesThat().resideInAPackage("..controller.v4..")
    .and().areAnnotatedWith(RequestMapping.class)
    .should().beAnnotatedWith(PreAuthorize.class)

// No new Groovy code
@ArchTest
noClasses().that().resideInAPackage("..db..")
    .should().dependOnClassesThat().haveNameMatching(".*groovy.*")

// No cyclic package dependencies
@ArchTest
slices().matching("org.octopusden.octopus.components.registry.(*)..")
    .should().beFreeOfCycles()
```

### 5.4 Database Reliability

| Fitness Function | Where | How |
|-----------------|-------|-----|
| Flyway migrations are valid | CI | Testcontainers: fresh DB → apply all migrations → verify schema |
| Flyway migrations are forward-only | CI | No deleted/modified migration files (checksum validation) |
| No schema drift | CI | Compare actual schema vs expected DDL (Flyway validate) |
| Optimistic locking works | CI | Concurrent update test → one succeeds, one gets `OptimisticLockException` |
| Foreign keys consistent | CI | Orphan record checks after import |
| Advisory lock prevents double-import | CI | Parallel import test → only one succeeds |

### 5.5 Runtime Reliability

| Mechanism | Description |
|-----------|-------------|
| **Circuit breaker** (Resilience4j) | DB calls wrapped in circuit breaker; if DB fails, opens circuit and returns error fast |
| **Graceful degradation** | During migration: if DB is down, Git-sourced components still served from Git |
| **Connection pool monitoring** | HikariCP metrics → alert if pool exhaustion > 80% |
| **Health check depth** | `/actuator/health` verifies: DB connection, Flyway status, Keycloak reachability |
| **Timeout enforcement** | DB query timeout = 5s; API timeout = 10s; prevents thread starvation |
| **Retry with backoff** | Transient DB failures retried 3x with exponential backoff (Spring Retry) |
| **Bulkhead** | Import operations isolated from read path (separate thread pool) |

**Failure mode matrix:**

| Failure | During migration (mixed sources) | After migration (all DB) | Mitigation |
|---------|----------------------------------|--------------------------|------------|
| PostgreSQL down | Git-sourced components OK, DB-sourced fail | Service unavailable | Replica failover + alert |
| PostgreSQL slow (>5s) | Circuit opens for DB, Git OK | Circuit opens, 503 | Scale DB, optimize queries |
| Keycloak down | Reads OK, v4 writes fail | Reads OK, v4 writes fail | Cache JWT public keys locally |
| Import fails mid-way | Component stays on Git (source not flipped) | N/A | Transactional: all-or-nothing per component |
| Cache corruption | Stale data served | Stale data served | TTL + cache clear endpoint |
| OOM / thread exhaustion | Both resolvers affected | Service degraded | Bulkhead, connection limits |

### 5.6 Migration Reliability

| Guarantee | How |
|-----------|-----|
| **Per-component rollback** | Flip `component_source.source` back to `git` — safe only if component was NOT edited in DB after import |
| **Dry-run mode** | `POST /rest/api/4/admin/migrate-component/{name}?dryRun=true` — validate without committing |
| **Migration progress tracking** | `GET /rest/api/4/admin/migration-status` — returns counts: git/db/failed per component |
| **Automatic validation gate** | Component not flipped to DB until equivalence check passes |
| **Git repo preserved** | Git repository remains read-only until all components validated in DB + bake-in period |
| **Time-boxed bake-in** | After last component migrated, keep Git repo available for N days before cleanup |
| **Audit trail of migration** | Each migration action logged in `audit_log` (who, when, which component, result) |

**Rollback is a one-way cutover after first DB write.** See [ADR-007](adr/007-dual-read-migration.md) for full rollback semantics per component state. There is no global "rollback to Git" switch.

### 5.7 Data Integrity

- Foreign key constraints with `ON DELETE CASCADE` for related configs
- `@Version` optimistic locking prevents lost updates
- PostgreSQL advisory locks for critical sections (DMS pattern)
- Transactional writes: component + configs + audit in single transaction
- `CHECK` constraints enforce valid enum values and owner polymorphism

### 5.8 Backup & Recovery

- PostgreSQL automated backups (daily full, continuous WAL archiving)
- Point-in-time recovery capability
- Audit log is append-only (no updates/deletes)
- RTO (Recovery Time Objective): < 1 hour
- RPO (Recovery Point Objective): < 5 minutes (WAL streaming)

### 5.9 Rollback Strategy

Rollback is per-component. There is no global "switch to Git" flag.

| Component state | Rollback to Git | Data loss? | Time |
|---|---|---|---|
| `source=git` (never imported) | Already on Git | No | — |
| `source=db` (imported, not edited) | Flip `component_source` to `git` | No | Seconds |
| `source=db` (edited in DB after import) | Flip to `git` | **Yes** — DB edits lost | Seconds |
| `source=db` (created via API/UI) | Cannot rollback | **Component disappears** | — |

| Level | Mechanism | Time to recover |
|-------|-----------|----------------|
| Single component (pre-edit) | Flip `component_source` to `git` | Seconds |
| DB schema | Flyway rollback migration | Minutes |
| Full system | Redeploy previous version (Git resolver still available during migration) | Minutes |

### 5.10 CI Pipeline Fitness Gates

Every PR must pass:
```
1. ArchUnit fitness functions       (architecture rules)
2. Flyway migration validation      (Testcontainers)
3. Unit tests                       (mappers, services)
4. Integration tests                (DB queries, transactions)
5. Contract tests                   (Feign client compatibility)
6. API snapshot tests               (v1/v2/v3 response structure)
```

Production deployment additionally runs:
```
7. Smoke test: health check + basic read
8. Canary: 10% traffic → monitor error rate for 10 min
9. Full rollout if error rate < 0.1%
```

## 6. Observability

### 6.1 Metrics (Prometheus / Actuator)
- `registry.read.duration` — read API latency histogram
- `registry.write.duration` — write API latency histogram
- `registry.cache.hit_ratio` — cache effectiveness
- `registry.migration.validation.match` / `registry.migration.validation.mismatch` — per-component import validation
- `registry.audit.events` — audit log write rate
- Standard JVM, DB pool, HTTP metrics via Actuator

### 6.2 Logging
- Structured JSON logs (Logback + SLF4J)
- Correlation ID in MDC for request tracing
- WARN level for import validation mismatches
- ERROR level for write failures

### 6.3 Health Checks
- `/actuator/health` — includes DB connection, Flyway migration status
- `/rest/api/2/components-registry/service/ping` — existing ping endpoint (backward compat)
- `/rest/api/2/components-registry/service/status` — includes migration progress (git/db component counts)

## 7. Compatibility

### 7.1 API Backward Compatibility
- v1/v2/v3 endpoints: **zero changes** to paths, methods, parameters, response DTOs
- Feign client `ComponentsRegistryServiceClient`: published without modifications
- OpenAPI spec: v4 endpoints documented separately

### 7.2 Browser Support (UI)
- Chrome (latest 2 versions)
- Firefox (latest 2 versions)
- Edge (latest 2 versions)
- Safari (latest 2 versions)

### 7.3 Database Version
- PostgreSQL 16+ (minimum)
- JDBC driver: latest PostgreSQL JDBC (42.x)

## 8. Maintainability

- Flyway versioned migrations (no manual DDL)
- Database schema documented in ERD diagrams
- ADRs for all significant decisions
- Integration tests with Testcontainers (no external DB dependency for CI)
- API documentation via OpenAPI/Swagger for v4 endpoints

### 8.1 Schema Extensibility

Adding new component properties must remain low-friction. See [ADR-010](adr/010-schema-extensibility.md).

| Property tier | Adding new field | DB migration? | Example |
|---|---|---|---|
| Tier 1 (stable core) | Flyway + Entity + Mapper + DTO | Yes | `system`, `archived` |
| Tier 2 (domain configs) | Flyway + Entity + Mapper + DTO | Yes | build, escrow, VCS tables |
| Tier 3 (extensible metadata) | DTO + mapper only | **No** | `releaseManager`, `labels`, future fields |

**Goal:** Adding a new metadata property (Tier 3) should require changes in **one service module only** (mapper + DTO), with no Flyway migration, no schema coordination across environments.
