# Technical Design Document: Components Registry DB Migration

## Status
**Draft** | Date: 2026-03-08

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

### 2.3 Feature Flag

```yaml
registry:
  storage: git    # git | db | routing | dual
```

- `git` — current behavior (default)
- `db` — reads from PostgreSQL
- `routing` — new components from DB, existing from Git (see [ADR-008](adr/008-component-level-routing.md))
- `dual` — reads from both, compares, logs discrepancies, returns Git result

Implemented via `@ConditionalOnProperty` or custom `@Configuration` selecting the `ComponentRegistryResolver` bean.

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

audit_log (standalone, indexed by entity_type + entity_id)
dependency_mappings (standalone key-value)
```

### 3.2 Polymorphic Owner Pattern

Build, escrow, VCS, distribution, and Jira configs can belong to either a **component** (defaults) or a **component_version** (overrides). This uses a CHECK constraint:

```sql
CONSTRAINT chk_owner CHECK (
    (component_id IS NOT NULL AND component_version_id IS NULL) OR
    (component_id IS NULL AND component_version_id IS NOT NULL)
)
```

### 3.3 Full DDL

See [plan.md — Database Schema section](../../plan.md) for complete SQL DDL with all tables, constraints, and indexes.

Flyway migration files will be placed in:
```
components-registry-service-server/src/main/resources/db/migration/
  V1__initial_schema.sql
  V2__indexes.sql
  V3__audit_log.sql
```

## 4. JPA Entities

### 4.1 Core Entity Example (Kotlin)

```kotlin
@Entity
@Table(name = "components")
class ComponentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    val name: String,

    var displayName: String? = null,
    var productType: String? = null,
    var componentOwner: String? = null,
    var system: String? = null,
    var clientCode: String? = null,
    var archived: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    val versions: MutableList<ComponentVersionEntity> = mutableListOf(),

    @Version
    var version: Long = 0,  // optimistic locking

    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
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

PUT    /rest/api/4/components/{id}
  Request:  ComponentUpdateRequest { displayName, productType, build, escrow, ... }
  Response: ComponentDetailResponse
  Auth:     EDIT_COMPONENTS
  Validation: Bean Validation (Jakarta @Valid, @NotBlank, @Size, etc.)

DELETE /rest/api/4/components/{id}
  Behavior: Sets archived=true (soft delete)
  Auth:     DELETE_COMPONENTS
```

#### Version Ranges
```
POST   /rest/api/4/components/{id}/versions
  Request:  VersionCreateRequest { versionRange, build, escrow, vcs, ... }
  Auth:     EDIT_COMPONENTS

PUT    /rest/api/4/components/{id}/versions/{versionId}
  Request:  VersionUpdateRequest { build, escrow, vcs, distribution, jira }
  Auth:     EDIT_COMPONENTS

DELETE /rest/api/4/components/{id}/versions/{versionId}
  Auth:     EDIT_COMPONENTS
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
```
POST   /rest/api/4/admin/import
  Behavior: Parses Groovy/Kotlin DSL from configured Git repo, writes to DB
  Auth:     IMPORT_DATA

GET    /rest/api/4/admin/export
  Response: JSON export of all components
  Auth:     ACCESS_COMPONENTS
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
```kotlin
@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    authServerClient: AuthServerClient,
    securityProperties: SecurityProperties
) : CloudCommonWebSecurityConfig(authServerClient, securityProperties)
```

### 6.3 Backward Compatibility
- v1/v2/v3 endpoints: **permit all** initially (existing consumers don't send JWT)
- v4 endpoints: **require authentication** via `@PreAuthorize`
- Gradual migration: consumers can be updated to pass JWT tokens over time

## 7. Data Migration

### 7.1 Migration Strategy: Component-Level Routing

New components created via API v4 / UI are stored directly in DB. Existing components remain in Git until individually imported and validated. See [ADR-008](adr/008-component-level-routing.md).

```
Phase 1: registry.storage=git       → current behavior
Phase 2: registry.storage=routing   → new components → DB, existing → Git
Phase 3: Per-component import       → existing components migrated one-by-one
Phase 4: registry.storage=db        → all in DB
Phase 5: Cleanup                    → remove Git code
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
@ConditionalOnProperty("registry.storage", havingValue = "routing")
class ComponentRoutingResolver(
    private val gitResolver: GitComponentRegistryResolver,
    private val dbResolver: DatabaseComponentRegistryResolver,
    private val sourceRegistry: ComponentSourceRegistry
) : ComponentRegistryResolver {

    override fun getComponentById(id: String): Component =
        if (sourceRegistry.isDbComponent(id)) dbResolver.getComponentById(id)
        else gitResolver.getComponentById(id)

    override fun getAllComponents(): List<Component> =
        gitResolver.getAllComponents().filter { sourceRegistry.isGitComponent(it.id) } +
        dbResolver.getAllComponents().filter { sourceRegistry.isDbComponent(it.id) }
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

registry:
  storage: git    # git | db | routing

# components-registry-service-cloud-prod.yml
db:
  host: <postgres-prod-host>.f1.svc.cluster.local
  port: 5432

registry:
  storage: db     # after migration complete

# components-registry-service-cloud-qa.yml
db:
  host: <postgres-qa-host>.f1.svc.cluster.local
  port: 5432

registry:
  storage: routing  # canary mode for QA
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
                "DB_NAME"    : "components-registry",
                "STORAGE_MODE": "db"
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

Update `components-registry-automation/okd/components-registry.yaml` to support DB mode:
- Add `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` parameters
- Add `STORAGE_MODE` parameter (default: `git`)
- Pass as Spring environment variables to container

## 11. Open Questions

1. Kotlin vs Java 21 for new code — see [ADR-002](adr/002-backend-language.md)
2. Config rollback/revert capability — deferred to post-migration
3. Webhook notifications on changes — deferred
4. Approval workflow — deferred
5. v1/v3 endpoint deprecation timeline — pending runtime access log analysis
