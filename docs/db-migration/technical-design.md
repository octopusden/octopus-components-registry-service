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

**Schema v2 is the authoritative model.** See [`schema-spec.md`](schema-spec.md) for the full reference (column-by-column inventory of all tables, resolve algorithm, API mapping, migration approach) and [ADR-014](adr/014-schema-v2.md) for the decision record and rejected alternatives.

### 3.1 Summary

Schema v2 (Model A') replaces the V1..V6 polymorphic-FK / JSONB-metadata model:

- **Polymorphic FK pairs removed.** All per-version data lives on `component_configurations` with a single FK to `components`.
- **No JSONB metadata.** Typed columns for all known fields; legitimate polymorphic JSON (audit_log, registry_config) kept as TEXT via `@JdbcTypeCode(SqlTypes.JSON)`.
- **`component_versions` table removed.** Version_range lives directly on `component_configurations` rows.
- **Per-attribute version-range overrides.** Each override is a row keyed by `(component_id, version_range, overridden_attribute)`: NULL (base), `'aspect.field'` (scalar), or marker (replacement of a child collection).
- **Unified VCS model.** SINGLE-VCS = 1 entry in `vcs_settings_entries` with `name = NULL`; MULTI-VCS = N named entries.
- **Distribution split** into four specialized child tables (Maven coords, file URLs, Docker images, DEB/RPM packages).
- **Aggregator grouping.** `component_groups` table + `components.component_group_id` FK preserve the DSL `components { ... }` nesting relationship that was previously lost in migration.
- **Reference dictionaries** for `labels`, `systems`, `tools` (admin-managed).
- **MIG-029 fixed.** `is_synthetic_base` flag on base rows; legacy variants enumeration skips synthetic bases.

See `schema-spec.md` §1 for the full table count and grouping, §2 for the ER diagram, §3 for resolve semantics, and §6 for migration approach.

### 3.2 Flyway

Single consolidated baseline `V1__schema.sql` replaces V1..V6 (project not yet in production; databases recreated). Hibernate runs in `validate` mode against the baseline. Tests use `ddl-auto: create-drop` directly from JPA annotations.

## 4. JPA Entities

The JPA entity layout for schema v2 is specified in [`schema-spec.md`](schema-spec.md) (column-by-column inventory of every table) and lands in Phase 2 (entity refactor). See [`implementation-progress.md`](implementation-progress.md) for current phase status.

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

GET    /rest/api/4/components?productType=&archived=&search=&owner=
  Response: Page<ComponentSummaryResponse>
  Auth:     ACCESS_COMPONENTS
  Filters:  productType, archived, search (name/displayName, ILIKE), owner (exact
            componentOwner). All independently optional, ANDed when combined.
            `system` is currently rejected with 400 (JPA Criteria + text[] gap).
  Contract: SYS-035 pins the owner filter (case-sensitive exact match).
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
  Response: Page<AuditEntry> { action, changedBy, changedAt, oldValue, newValue, diff, source }
  Auth:     ACCESS_AUDIT

GET    /rest/api/4/audit/recent
  Response: Page<AuditEntry>, default sort changedAt DESC
  Auth:     ACCESS_AUDIT
  Filters:  Optional query params (independent, ANDed when combined; SYS-036):
              entityType   — currently only "Component" (case-sensitive); other
                             types reserved for future audit instrumentation
              entityId     — UUID; combine with entityType for entity-scoped history
              changedBy    — username from audit_log.changed_by (CurrentUserResolver)
              source       — currently only "api" or "git-history"; other values
                             reserved for future writers
              action       — "CREATE" | "UPDATE" | "DELETE" | "RENAME" | "ARCHIVE"
              from, to     — ISO-8601 instants; half-open [from, to) on changed_at
  Note:     `audit_log.changed_by` is populated from CurrentUserResolver
            (preferred_username from the authenticated JWT, fallback "system" for
            background jobs). Fixes the gap where API-driven audit rows used to
            store NULL.
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
- `/swagger-ui/**`, `/webjars/**`, `/v2/api-docs`, `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/v3/api-docs/swagger-config`, `/swagger-resources/**` → `permitAll()` (springdoc-openapi assets; configured in this class, not inherited from parent).
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

Every `applicationEventPublisher.publishEvent(AuditEvent(...))` call site sets `changedBy = currentUserResolver.currentUsername()`. `CurrentUserResolver` reads the active Spring Security context:
- `JwtAuthenticationToken` → `preferred_username` claim (Keycloak's canonical username), falling back to `auth.name` (JWT subject);
- non-JWT `Authentication` (rare) → `auth.name`;
- no authenticated context (background jobs, async tasks outside an HTTP thread) → `"system"`.

The fallback path is exercised by code paths that don't carry a request context. `/admin/migrate-history` is a special case: it sets `changedBy` from the git author signature (`"Name <email>"`) rather than from `CurrentUserResolver`, because the historical event was originally authored by that git committer — see `GitHistoryImportServiceImpl`. SYS-036 acceptance criterion 3 (filter by `changedBy`) depends on this wiring being in place; tests live under `AuditLogFilterTest`.

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
