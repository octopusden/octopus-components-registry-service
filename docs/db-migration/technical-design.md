# Technical Design Document: Components Registry DB Migration

## Status
**Living document** | Last updated: 2026-04-29 (was Draft 2026-03-08)

## 1. Overview

This document describes the technical design for migrating the Components Registry Service from Git-based storage (Groovy/Kotlin DSL) to PostgreSQL, adding CRUD API (v4), audit logging, Keycloak-based authorization, and a web UI.

**Key constraint:** All existing REST API endpoints (v1/v2/v3, 34 endpoints, 28 Feign client methods) must remain backward compatible.

## 2. Architecture

### 2.1 Current State

```
Git Repository (.groovy/.kts files)
        ↓ JGit clone
ConfigLoader + EscrowConfigurationLoader
        ↓ parse
EscrowConfiguration (in-memory)
        ↓
ComponentRegistryResolver (interface)
        ↓
REST Controllers (v1, v2, v3) → Feign Clients (7+ consumers)
```

### 2.2 Target State

```
┌─────────────────────────────────────────────────────────────┐
│         Components Registry UI (React 19 + shadcn/ui)       │
├─────────────────────────────────────────────────────────────┤
│                    API Gateway (Keycloak)                    │
├─────────────────────────────────────────────────────────────┤
│   REST API v1/v2/v3 (read, unchanged)  │  REST API v4 (CRUD) │
├────────────────┬───────────────────────┴────────────────────┤
│  Security      │  Audit Event Listener                      │
│  (octopus-     │  (@TransactionalEventListener              │
│   security-    │   → audit_log table)                       │
│   common)      │                                            │
├────────────────┴────────────────────────────────────────────┤
│              Service Layer                                  │
│  ComponentRegistryResolver (Read) ← unchanged interface     │
│  ComponentManagementService (Write) ← new                   │
├─────────────────────────────────────────────────────────────┤
│          Repository Layer (Spring Data JPA)                  │
│  ComponentRepository, VersionRepository, etc.                │
├─────────────────────────────────────────────────────────────┤
│                    PostgreSQL 16+                            │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 Component-Source Routing

There is no global mode flag. The system always uses `ComponentRoutingResolver`, which looks up the source for each component in the `component_source` table. See [ADR-007](adr/007-dual-read-migration.md).

```sql
CREATE TABLE component_source (
    component_name  VARCHAR(255) PRIMARY KEY,
    source          VARCHAR(10) NOT NULL DEFAULT 'git',  -- 'git' | 'db'
    migrated_at     TIMESTAMP,
    migrated_by     VARCHAR(255)
);
```

- On initial deployment: all existing components have `source = 'git'` → system behaves as before
- New components created via API/UI: inserted with `source = 'db'`
- After successful import + validation: updated to `source = 'db'`

## 3. Database Schema

### 3.1 Entity-Relationship Overview

```
components (1) ──→ (N) component_versions
    │                       │
    ├──→ build_configurations ←──┤
    ├──→ escrow_configurations ←─┤
    ├──→ vcs_settings ←──────────┤
    ├──→ distributions ←─────────┤
    ├──→ jira_component_configs ←┤
    │
    ├──→ component_artifact_ids
    └──→ components (self-ref: parent_component_id)

distributions (1) ──→ (N) distribution_artifacts
                  ──→ (N) distribution_security_groups

vcs_settings (1) ──→ (N) vcs_settings_entries (for MULTIPLY type)

audit_log (standalone, indexed by entity_type + entity_id, source ∈ {api, git-history})
dependency_mappings (standalone key-value)
git_history_import_state (single-row idempotency state for /admin/migrate-history)
field_overrides (per-component per-field per-version-range overrides)
component_artifact_ids (owner XOR: component_id OR component_version_id, V4)
```

### 3.2 Polymorphic Owner Pattern

Build, escrow, VCS, distribution, and Jira configs can belong to either a **component** (defaults) or a **component_version** (overrides). This uses a CHECK constraint:

```sql
CONSTRAINT chk_owner CHECK (
    (component_id IS NOT NULL AND component_version_id IS NULL) OR
    (component_id IS NULL AND component_version_id IS NOT NULL)
)
```

> **Note — per-field version overrides:** The schema will support independent version ranges per parameter field (e.g., `buildSystem` overridden for `[1.0, 2.0)` while `jiraProjectKey` is overridden for `[1.5, 2.5)`). Overlapping ranges across different fields are allowed; overlapping ranges for the same field are forbidden. The final schema design for per-field overrides is deferred to implementation.

### 3.3 Schema Extensibility

Component properties are classified into three tiers by stability. See [ADR-010](adr/010-schema-extensibility.md) for full rationale.

| Tier | Storage | Fields | Adding new field |
|------|---------|--------|------------------|
| 1 — Stable core | Columns | `name`, `component_owner`, `archived`, `system`, `product_type`, `client_code`, `solution`, `parent_component_id` | Flyway + Entity + Mapper + DTO |
| 2 — Domain configs | Separate tables | build, escrow, VCS, distribution, jira | Flyway + Entity + Mapper + DTO |
| 3 — Extensible metadata | `metadata JSONB` | `releaseManager`, `securityChampion`, `labels`, `doc`, `copyright`, `releasesInDefaultBranch` | DTO only |

```sql
-- metadata column on components table
ALTER TABLE components ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';
CREATE INDEX idx_components_metadata ON components USING GIN (metadata);
```

**Promotion path:** if a Tier 3 field becomes critical for performance or joins, it can be promoted to a Tier 1 column via Flyway migration + backfill from JSONB.

### 3.4 Full DDL

Complete DDL is maintained in Flyway migration files (see §3.1 ERD above for entity relationships). The full SQL will be finalized at implementation start and placed in:


Flyway migration files live at `components-registry-service-server/src/main/resources/db/migration/`:

| Version | File | Purpose |
|---|---|---|
| V1 | `V1__initial_schema.sql` | Initial schema — components, versions, build/escrow/vcs/distribution/jira configs, audit_log, field_overrides, component_artifact_ids, etc. |
| V2 | `V2__indexes.sql` | Performance indexes on hot lookup paths. |
| V3 | `V3__text_columns.sql` | Widen VARCHAR(500) columns to TEXT to fix overflow regressions. |
| V4 | `V4__artifact_ids_version_level.sql` | Allow `component_artifact_ids` to belong to either a component or a `component_version` (XOR `CHECK` + nullable `component_id`, new nullable `component_version_id`). Mirrors the polymorphic owner pattern used by other config tables. |
| V5 | `V5__audit_source_and_history_state.sql` | Add `audit_log.source VARCHAR(20) NOT NULL DEFAULT 'api'` (distinguishes runtime API events from synthetic backfill rows produced by `/admin/migrate-history`); plain `CREATE INDEX idx_audit_source` (acceptable while the table is small — see file header for the CONCURRENTLY workaround if replayed against a large table). Create `git_history_import_state` (single-row idempotency state, no resume support; PK `import_key`, fields `target_ref`, `target_sha`, `status`, `updated_at`). |

## 4. JPA Entities

### 4.1 Core Entity Example (Kotlin)

```kotlin
@Entity
@Table(name = "components")
class ComponentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    // name is the public API identifier (Component.id in v1/v2/v3 is the component name)
    @Column(nullable = false, unique = true)
    val name: String,

    // Tier 1 — stable core (columns)
    @Column(name = "component_owner", nullable = false)
    var componentOwner: String,

    var productType: String? = null,
    // NOTE: current model uses Set<String> for system (Component.system: Set<String>)
    // DB: stored as array or join table; mapped to Set<String> in DTO
    @Column(columnDefinition = "text[]")
    var system: Set<String> = emptySet(),
    var clientCode: String? = null,
    var archived: Boolean = false,
    var solution: Boolean? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,

    // Tier 3 — extensible metadata (JSONB)
    // Contains: displayName, releaseManager, securityChampion,
    //           labels, doc, copyright, releasesInDefaultBranch, and future fields
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),

    // Tier 2 — domain configs (separate tables, via relations)
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    val versions: MutableList<ComponentVersionEntity> = mutableListOf(),

    @Version
    var version: Long = 0,  // optimistic locking

    @CreationTimestamp
    @Column(updatable = false)
    val createdAt: Instant? = null,

    @UpdateTimestamp
    var updatedAt: Instant? = null
)
```

### 4.2 Repository Example

```kotlin
interface ComponentRepository : JpaRepository<ComponentEntity, UUID> {
    fun findByName(name: String): ComponentEntity?
    fun findBySystem(system: String): List<ComponentEntity>
    fun findByArchivedFalse(): List<ComponentEntity>

    @Query("SELECT c FROM ComponentEntity c LEFT JOIN FETCH c.versions WHERE c.name = :name")
    fun findByNameWithVersions(@Param("name") name: String): ComponentEntity?
}
```

## 5. API Design

### 5.1 Existing API (unchanged)

All 34 endpoints across v1/v2/v3 remain identical. The only change is the backing `ComponentRegistryResolver` implementation.

### 5.2 New API v4

#### Components CRUD
```
POST   /rest/api/4/components
  Request:  ComponentCreateRequest { name, displayName, productType, ... }
  Response: ComponentDetailResponse { id, name, ..., versions[], build, escrow, ... }
  Auth:     EDIT_COMPONENTS

GET    /rest/api/4/components/{id}
  Response: ComponentDetailResponse (full tree with all nested configs)
  Auth:     ACCESS_COMPONENTS

PATCH  /rest/api/4/components/{id}
  Request:  ComponentUpdateRequest { version, displayName, productType, build, escrow, ... }
  Response: ComponentDetailResponse
  Auth:     EDIT_COMPONENTS
  Headers:  If-Match (optional, alternative to version field for optimistic locking)
  Validation: Bean Validation (Jakarta @Valid, @NotBlank, @Size, etc.)
  Semantics: JSON Merge Patch (RFC 7396):
    - field present with value → set
    - field absent → not changed
    - field set to null → clear/reset to default (remove override)

DELETE /rest/api/4/components/{id}
  Behavior: Sets archived=true (soft delete)
  Auth:     DELETE_COMPONENTS
```

#### Field Version Overrides
```
POST   /rest/api/4/components/{id}/field-overrides
  Request:  FieldOverrideCreateRequest { fieldPath, versionRange, value }
  Example:  { "fieldPath": "build.buildSystem", "versionRange": "[1.0, 2.0)", "value": "MAVEN" }
  Auth:     EDIT_COMPONENTS
  Validation: No overlap with existing ranges for the same field (409 Conflict)

PATCH  /rest/api/4/components/{id}/field-overrides/{overrideId}
  Request:  FieldOverrideUpdateRequest { versionRange?, value? }
  Auth:     EDIT_COMPONENTS

DELETE /rest/api/4/components/{id}/field-overrides/{overrideId}
  Auth:     EDIT_COMPONENTS

GET    /rest/api/4/components/{id}/field-overrides
  Response: List of all field overrides for the component, grouped by field path
  Auth:     ACCESS_COMPONENTS
```

#### Audit
```
GET    /rest/api/4/audit/{entityType}/{entityId}?page=0&size=20
  Response: Page<AuditEntry> { action, changedBy, changedAt, oldValue, newValue, diff }
  Auth:     ACCESS_AUDIT

GET    /rest/api/4/audit/recent?page=0&size=50
  Response: Page<AuditEntry>
  Auth:     ACCESS_AUDIT
```

#### Admin
All endpoints under `/rest/api/4/admin/**` are gated at the class level by `@PreAuthorize("@permissionEvaluator.canImport()")` (`IMPORT_DATA`). See `AdminControllerV4.kt` for source of truth.

```
POST   /rest/api/4/admin/migrate-component/{name}?dryRun={bool}
  Response: MigrationResult
  Behavior: Imports a single component from Git DSL into DB (synchronous).

POST   /rest/api/4/admin/migrate-components
POST   /rest/api/4/admin/import         (alias of migrate-components)
  Response: BatchMigrationResult
  Behavior: Bulk synchronous import. Long-running. Prefer the async variant
            below for full migrations.

POST   /rest/api/4/admin/migrate
  Response: 202 Accepted (newly started) or 409 Conflict (existing RUNNING
            job — re-run guard) with body MigrationJobResponse.
  Behavior: Starts async migration on a background executor. Re-running while
            a job is RUNNING is a no-op (returns 409 with the existing state),
            so the SPA "attaches" rather than spawning duplicates.
  Contract: MIG-027 in requirements-migration.md.

GET    /rest/api/4/admin/migrate/job
  Response: 200 OK with MigrationJobResponse, or 404 if no job has been
            started since pod startup.
  Behavior: Polled by Portal MigrationPanel. State is in-memory; pod restart
            loses progress and the next poll yields 404. Tracked as MIG-028.

POST   /rest/api/4/admin/migrate-defaults
  Response: Map of imported keys
  Behavior: Imports Defaults.groovy → component_defaults (build, jira,
            distribution, vcs, escrow, doc, deprecated, octopusVersion).

GET    /rest/api/4/admin/migration-status
  Response: MigrationStatus { dbCount, gitCount, total }

POST   /rest/api/4/admin/validate-migration/{name}
  Response: ValidationResult
  Behavior: Deep-compare Git vs DB resolver output for the named component.

POST   /rest/api/4/admin/migrate-history?toRef={ref}&reset={bool}
  Response: HistoryImportResult { targetRef, targetSha, processedCommits,
            skippedNoGroovy, skippedParseError, skippedUnknownNames,
            auditRecords, durationMs }
  Behavior: Backfills git commit history into audit_log with
            source = 'git-history'. Idempotent through git_history_import_state
            (single-row, INSERT … ON CONFLICT DO NOTHING claim). reset=true
            clears state and re-runs.
  Contract: MIG-026.

GET    /rest/api/4/admin/export
  Response: { "status": "not_implemented" } — stub, not yet implemented.
```

#### Configuration (Field Visibility & Defaults)

See [ADR-011](adr/011-field-configuration.md) for full rationale.

```
GET    /rest/api/4/config/field-config
  Response: Field configuration (visibility, options, descriptions)
  Auth:     ACCESS_COMPONENTS (read-only, used by UI to render forms)

GET    /rest/api/4/config/component-defaults
  Response: Default values for new components (used by UI to pre-fill create form)
  Auth:     ACCESS_COMPONENTS (read-only)

GET    /rest/api/4/admin/config/field-config
PUT    /rest/api/4/admin/config/field-config
  Request:  JSON — field visibility/editability rules per section
  Auth:     ADMIN only

GET    /rest/api/4/admin/config/component-defaults
PUT    /rest/api/4/admin/config/component-defaults
  Request:  JSON — default values for new components (replaces Defaults.groovy)
  Auth:     ADMIN only
```

The API schema (v1/v2/v3/v4 request/response DTOs) is **identical across all deployments**. Field configuration affects only:
- UI rendering (which fields are shown/hidden/readonly)
- Server-side defaults (values applied when field is absent in create request)
- Server-side enforcement (hidden/readonly fields ignore client-provided values)

#### Service info & current user

These endpoints are cross-cutting (Portal footer + identity display) and live outside the component CRUD tree.

```
GET    /rest/api/4/info
  Response: { "name": "<artifact-name>", "version": "<build-version>" }
  Auth:     Anonymous (permitAll). Sourced from BuildProperties.
  Contract: SYS-033 in requirements-common.md.

GET    /auth/me           (note: top-level, NOT under /rest/api/4)
  Response: User { username, roles, groups } from octopus-cloud-commons
  Auth:     Authenticated (returns 401 without JWT).
  Contract: SYS-034 in requirements-common.md.
```

## 6. Security

### 6.1 Dependencies
```gradle
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.security:spring-security-oauth2-resource-server")
implementation("org.springframework.security:spring-security-oauth2-jose")
implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:2.0.15")
```

### 6.2 Configuration

Implemented in `WebSecurityConfig.kt` (extends `CloudCommonWebSecurityConfig` from octopus-security-common). Filter-chain rules (canonical source = `WebSecurityConfig.kt`):

- `/rest/api/{1,2,3}/**` → `permitAll()` (legacy Feign clients without JWT).
- `GET /rest/api/4/components/**`, `GET /rest/api/4/config/**` → `permitAll()`. Anonymous users implicitly carry `ROLE_ANONYMOUS` which the `octopus-security.roles` map binds to `ACCESS_COMPONENTS`, so `@PreAuthorize` annotations on GET endpoints also pass without auth.
- `GET /rest/api/4/info` → `permitAll()` (anonymous build-info for Portal footer; SYS-033).
- All other `/rest/api/4/**` (writes, admin, audit) → `authenticated()` + `@PreAuthorize`.
- `/auth/**` → `authenticated()` (covers `/auth/me`, SYS-034).
- `/actuator/health`, `/actuator/health/**` → `permitAll()`. All other `/actuator/**` paths fall through to `authenticated()` — only health probes are anonymous (see inline comment in `WebSecurityConfig.kt:49-54`).
- `/swagger-ui/**`, `/webjars/**`, `/v3/api-docs/swagger-config`, `/swagger-resources/**` → `permitAll()` (springdoc-openapi assets; configured in this class, not inherited from parent).
- CSRF disabled (CRS is a Resource Server; CSRF is enforced by Portal at the gateway level — see ADR-012).

### 6.3 Permission Evaluator

`PermissionEvaluator` extends `BasePermissionEvaluator` (cloud-commons). Method-level helpers wired into `@PreAuthorize` SpEL:

| Method | Required permission | Used on |
|---|---|---|
| `canEditComponent(name)` | `EDIT_COMPONENTS` | `PATCH /components/{id}` (plain edit; `POST /components` uses `hasPermission('EDIT_COMPONENTS')` directly — no `name` arg available) |
| `canArchiveComponent(name)` | `ARCHIVE_COMPONENTS` | `PATCH /components/{id}` when `archived` is in payload |
| `canRenameComponent(name)` | `RENAME_COMPONENTS` | `PATCH /components/{id}` when `name` is in payload |
| `canDeleteComponent(name)` | `DELETE_COMPONENTS` | `DELETE /components/{id}` |
| `canImport()` | `IMPORT_DATA` | class-level on `AdminControllerV4` (covers all admin endpoints incl. `/migrate`, `/migrate-history`, etc.) |
| `hasPermission("ACCESS_AUDIT")` | `ACCESS_AUDIT` | `AuditControllerV4` |

The `PATCH /components/{id}` SpEL guard combines these (the path variable is a `UUID`; the helper takes `String`, so the SpEL passes `#id.toString()`):
```
@PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') and @permissionEvaluator.canEditComponent(#id.toString()) and (#request.archived == null or @permissionEvaluator.canArchiveComponent(#id.toString())) and (#request.name == null or @permissionEvaluator.canRenameComponent(#id.toString()))")
```
Plain edits stay on `EDIT_COMPONENTS`; archive/rename payloads fail closed with 403 for anyone without the extra permission.

Per-component ownership (`componentOwner`, `releaseManager`) is a deferred layer — the permission names are stable across that future change so the role map does not need to move.

### 6.4 Audit `changedBy` wiring

Every `applicationEventPublisher.publishEvent(AuditEvent(...))` call site reads the username from `SecurityService.getCurrentUser().username` (cloud-commons), with a fallback of `"system"` for events triggered outside an authenticated context (e.g. background jobs). The fallback path is exercised by `/admin/migrate-history` rows, which still want a non-null `changed_by` for audit consistency.

### 6.5 Backward Compatibility
- v1/v2/v3 endpoints: **permit all** (existing 7+ Feign client consumers don't send JWT).
- v4 reads (`GET /components/**`, `/config/**`): **permit all** through the filter chain; method-level `@PreAuthorize` passes thanks to `ROLE_ANONYMOUS → ACCESS_COMPONENTS` in the role map.
- v4 writes/admin/audit: **require JWT** + the permission named in ADR-004.
- Gradual migration: consumers can be updated to pass JWT tokens over time. Phase 3 (close anonymous reads on v1/v2/v3) requires coordinated update of all consumers — timeline TBD.

## 7. Data Migration

### 7.1 Migration Strategy: Component-Source Routing

New components created via API v4 / UI are stored directly in DB. Existing components remain in Git until individually imported and validated. There is no global mode flag — `ComponentRoutingResolver` always routes per component based on the `component_source` table. See [ADR-007](adr/007-dual-read-migration.md).

```
Phase 1: Deploy ComponentRoutingResolver + DB schema
         All components have source=git → system behaves as before
         UI not yet available for git-sourced components

Phase 2: New components created via API/UI → source=db
         Existing components unchanged (source=git)
         UI works only for DB-sourced components

Phase 3: Per-component import (gradual)
         For each component:
           1. Load from Git DSL → write to DB
           2. Validate: compare Git vs DB resolver output (deep equals)
           3. If match → flip source to 'db'
           4. If mismatch → keep source='git', log discrepancy report

Phase 4: All components source=db
         Git resolver becomes unused

Phase 5: Remove Git resolver code, drop component_source table
```

### 7.2 Import Flow (per-component)
```
1. ConfigLoader.loadGroovyDSL()          → ConfigObject
2. EscrowConfigurationLoader.parse()     → EscrowConfiguration (in-memory)
3. ComponentsRegistryScriptRunner.load() → Kotlin DSL components
4. Mapper: EscrowConfiguration → JPA Entities
5. Repository.saveAll()                  → PostgreSQL
6. Validation: compare Git vs DB read result for this component
7. If match → update component_source.source = 'db'
```

### 7.3 Routing Resolver
```kotlin
@Component
@Primary
class ComponentRoutingResolver(
    private val gitResolver: GitComponentRegistryResolver,
    private val dbResolver: DatabaseComponentRegistryResolver,
    private val sourceRegistry: ComponentSourceRegistry
) : ComponentRegistryResolver {

    override fun getComponentById(id: String): EscrowModule? =
        if (sourceRegistry.isDbComponent(id)) dbResolver.getComponentById(id)
        else gitResolver.getComponentById(id)

    override fun getComponents(): MutableCollection<EscrowModule> {
        val gitComponents = gitResolver.getComponents()
            .filter { sourceRegistry.isGitComponent(it.moduleName) }
        val dbComponents = dbResolver.getComponents()
            .filter { sourceRegistry.isDbComponent(it.moduleName) }
        return (gitComponents + dbComponents).toMutableList()
    }

    // All other 20+ methods follow the same pattern:
    // delegate to gitResolver or dbResolver based on sourceRegistry
}
```

## 8. Testing Strategy & Fitness Functions

### 8.1 Testing Pyramid

| Layer | Tool | What | Runs in |
|-------|------|------|---------|
| Unit | JUnit 5 + Mockito | Service logic, DSL→Entity mappers, DTO converters | Every PR |
| Integration | Testcontainers (PostgreSQL) | Repository queries, Flyway migrations, transactions | Every PR |
| Contract | Spring Cloud Contract / Pact | Feign client compatibility (28 methods) | Every PR |
| API Snapshot | Custom JSON diff | v1/v2/v3 response structure unchanged | Every PR |
| Architecture | ArchUnit | Layering, security annotations, no cycles | Every PR |
| Performance | k6 / Gatling | Response time p95 < 100ms (read), < 500ms (write) | Nightly / pre-release |
| Migration | Custom + Testcontainers | Import DSL → DB → compare with Git result | Pre-migration |
| E2E | Playwright (UI) | UI flows: list, edit, audit, login | Pre-release |

### 8.2 Data Equivalence Tests

Critical for migration confidence — verify DB returns identical data to Git:

```kotlin
@ParameterizedTest
@MethodSource("allComponentNames")
fun `DB resolver returns same result as Git resolver`(componentName: String) {
    val gitResult = gitResolver.getComponentById(componentName)
    val dbResult = dbResolver.getComponentById(componentName)
    assertThat(dbResult).usingRecursiveComparison()
        .ignoringCollectionOrder()
        .isEqualTo(gitResult)
}
```

### 8.3 Failure Mode Tests

```kotlin
@Test fun `routing mode serves Git components when DB is down`()
@Test fun `circuit breaker opens after 5 consecutive DB failures`()
@Test fun `concurrent update on same component triggers OptimisticLockException`()
@Test fun `parallel import of same component blocked by advisory lock`()
@Test fun `import rolls back completely on mapper failure`()
```

See [Non-Functional Specification §5](non-functional-spec.md#5-reliability--fitness-functions) for complete fitness function catalog.

## 9. New Dependencies (build.gradle)

```gradle
// Database
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
runtimeOnly("org.postgresql:postgresql")
implementation("org.flywaydb:flyway-core")

// Security
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.security:spring-security-oauth2-resource-server")
implementation("org.springframework.security:spring-security-oauth2-jose")
implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:${cloudCommonsVersion}")

// Cache
implementation("org.springframework.boot:spring-boot-starter-cache")
implementation("com.github.ben-manes.caffeine:caffeine")

// Test
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:junit-jupiter")
```

## 10. Deployment & Configuration

### 10.1 Infrastructure Overview

Services are deployed to OKD using:
- **Helm** (`spring-cloud` chart from `<gitserver>/f1/service-deployment` → `helm-charts/spring-cloud/`)
- **Spring Cloud Config** (externalized YAML in `<gitserver>/f1/service-config` repository)
- **`octopus-oc-template-gradle-plugin`** for functional testing in OKD

### 10.2 Configuration Changes (`<gitserver>/f1/service-config`)

New/updated files in `service-config`:

```yaml
# components-registry-service.yml (base)
spring:
  datasource:
    url: jdbc:postgresql://${db.host}:${db.port}/components-registry
    username: ${db.username}
    password: ${db.password}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

# No global mode flag — ComponentRoutingResolver always routes
# per component based on component_source table.
# During migration: some components source=git, others source=db.
# After migration: all components source=db, Git resolver unused.

# components-registry-service-cloud-prod.yml
db:
  host: <postgres-prod-host>.f1.svc.cluster.local
  port: 5432

# components-registry-service-cloud-qa.yml
db:
  host: <postgres-qa-host>.f1.svc.cluster.local
  port: 5432
```

Keycloak config follows existing pattern from `application.yml`:
```yaml
auth-server:
  url: https://f1-base-services${domain.sub}.${domain.main}/auth
  realm: F1    # or f1-qa for QA

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/certs
```

### 10.3 Helm Deployment (`<gitserver>/f1/service-deployment`)

Extend existing deployment spec in `okd/deployments/`:

```yaml
# okd/deployments/production/components-registry-service.yml
image: f1/components-registry-service
tag: <version>
replicas: 2
```

No changes to the Helm chart itself — same `spring-cloud` chart used by all services.

### 10.4 PostgreSQL Provisioning

Follow DMS pattern — dedicated database per service:
- **Prod**: `components-registry` database on existing PostgreSQL cluster
- **QA**: `components-registry` database on QA PostgreSQL
- Connection via JDBC URL in `service-config` YAML profiles (see `<gitserver>/f1/service-config`)

### 10.5 Functional Testing with oc-template plugin

Extend `components-registry-automation/build.gradle`:

```groovy
ocTemplate {
    // ... existing config ...

    service("postgres") {
        templateFile = layout.projectDirectory.file("okd/postgres.yaml")
        parameters.set(commonOkdParameters + [
            "DATABASE_NAME": "components-registry"
        ])
    }

    service("comp-reg") {
        templateFile = layout.projectDirectory.file("okd/components-registry.yaml")
        // existing config + new DB params
        parameters.set(
            commonOkdParameters + [
                // ... existing params ...
                "DB_HOST"    : ocTemplate.getOkdInternalHost("postgres"),
                "DB_PORT"    : "5432",
                "DB_NAME"    : "components-registry"
            ]
        )
        dependsOn.set(["postgres"])  // wait for DB before starting service
    }
}
```

New OKD template for PostgreSQL (`components-registry-automation/okd/postgres.yaml`):
```yaml
apiVersion: template.openshift.io/v1
kind: Template
metadata:
  name: postgres-template
objects:
  - apiVersion: v1
    kind: Pod
    metadata:
      name: ${DEPLOYMENT_PREFIX}-postgres
      labels:
        app.kubernetes.io/name: ${DEPLOYMENT_PREFIX}-postgres
    spec:
      restartPolicy: Never
      activeDeadlineSeconds: ${{ACTIVE_DEADLINE_SECONDS}}
      containers:
        - name: postgres
          image: postgres:16
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_DB
              value: ${DATABASE_NAME}
            - name: POSTGRES_USER
              value: test
            - name: POSTGRES_PASSWORD
              value: test
          readinessProbe:
            tcpSocket:
              port: 5432
            initialDelaySeconds: 5
            periodSeconds: 3
  - apiVersion: v1
    kind: Service
    metadata:
      name: ${DEPLOYMENT_PREFIX}-postgres-service
    spec:
      ports:
        - port: 5432
          targetPort: 5432
      selector:
        app.kubernetes.io/name: ${DEPLOYMENT_PREFIX}-postgres
```

### 10.6 OKD Template Update for Service

Update `components-registry-automation/okd/components-registry.yaml` to support DB:
- Add `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` parameters
- Pass as Spring environment variables to container

## 11. Open Questions

1. Kotlin vs Java 21 for new code — see [ADR-002](adr/002-backend-language.md)
2. Config rollback/revert capability — deferred to post-migration
3. Webhook notifications on changes — deferred
4. Approval workflow — deferred
5. v1/v3 endpoint deprecation timeline — pending runtime access log analysis
