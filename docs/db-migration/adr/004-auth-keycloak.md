# ADR-004: Authentication & Authorization via Keycloak

## Status
Accepted. **Implemented:** 2026‑04‑28 (commit `b97fad2`, PR #150 — Keycloak auth + v4 `@PreAuthorize`).

## Context

The service currently has **no authentication or authorization**. Any client can call any endpoint. With the addition of write operations (CRUD API v4, Web UI), we need:
- Authentication: verify user identity
- Authorization: role-based access to endpoints
- Audit trail: who changed what

### Current Security Architecture (from [octopus-api-gateway PR#28](https://github.com/octopusden/octopus-api-gateway/pull/28))

> **Note:** PR#28 is a colleague's attempt to document the current security model. Treat as reference, not as ground truth.

```
┌─────────────────────┐      ┌────────────────────┐
│   Browser / UI      │      │  Service (Feign)    │
│   (OAuth2 login)    │      │  (Basic Auth / JWT) │
└────────┬────────────┘      └─────────┬──────────┘
         │                             │
         ▼                             ▼
┌─────────────────────────────────────────────────┐
│            API-Gateway (Spring Cloud Gateway)    │
│  - OAuth2 Client (Authorization Code flow)       │
│  - BasicAuthFilter: Basic Auth → offline JWT     │
│  - TokenRelay filter: forwards JWT to backends   │
│  - Routes: /components-registry-service/**       │
│            with StripPrefix=1                    │
└─────────────────────────┬───────────────────────┘
                          │ Bearer JWT
                          ▼
┌─────────────────────────────────────────────────┐
│     components-registry-service (this service)   │
│     Currently: NO auth, accepts all requests     │
│     Target: cloud-commons Resource Server        │
└─────────────────────────────────────────────────┘
```

**Two authentication flows exist:**

1. **Browser (UI)**: OAuth2 Authorization Code → Keycloak SSO → session + TokenRelay
2. **API (Basic Auth)**: `BasicAuthFilter` in API-Gateway converts `user:passwd` → offline JWT via Keycloak token endpoint → TokenRelay

**Key insight:** consumers via API-Gateway already receive JWT (either from OAuth2 or BasicAuth conversion). But **direct Feign client consumers** (dms-service, rms, etc.) likely call components-registry-service **without going through API-Gateway** and **without JWT**. This means v1/v2/v3 must be `permitAll()` initially.

### Existing cloud-commons pattern (from DMS)

The `octopus-cloud-commons:octopus-security-common` library provides:

| Class | Role |
|-------|------|
| `CloudCommonWebSecurityConfig` | Base `@EnableWebSecurity` config; OAuth2 Resource Server with JWT; permits actuator/swagger |
| `AuthServerClient` | REST client to Keycloak (userinfo, token generation/refresh, OpenID discovery) |
| `UserInfoGrantedAuthoritiesConverter` | JWT → `ROLE_*` + `GROUP_*` GrantedAuthorities via Keycloak userinfo endpoint |
| `SecurityService` | `getCurrentUser()` → `User(username, roles, groups)` from SecurityContext |
| `BasePermissionEvaluator` | `hasPermission(permission)` — checks user roles against `octopus-security.roles` mapping |
| `SecurityProperties` | `@ConfigurationProperties("octopus-security")` — role→permissions map from YAML |

## Considered Options

### Option A: octopus-security-common (DMS pattern) — recommended
- `WebSecurityConfig extends CloudCommonWebSecurityConfig`
- `PermissionEvaluator extends BasePermissionEvaluator`
- Spring Security OAuth2 Resource Server (JWT validation)
- Role→Permission mapping in application.yml
- `@PreAuthorize("@permissionEvaluator.hasPermission('...')")` on controllers

### Option B: Spring Security standalone
- Direct Spring Security OAuth2 Resource Server without cloud-commons wrapper
- Custom security configuration from scratch
- More control but more code to write and no ecosystem consistency

### Option C: API Gateway only
- All auth handled at gateway level, service trusts all requests
- No fine-grained authorization within the service
- Can't enforce component-level permissions

## Decision

**Option A: octopus-security-common** — follows the established DMS pattern.

### Dependencies
```gradle
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.security:spring-security-oauth2-resource-server")
implementation("org.springframework.security:spring-security-oauth2-jose")
implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:2.0.15")
```

### Implementation

#### WebSecurityConfig
```kotlin
@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    authServerClient: AuthServerClient,
    securityProperties: SecurityProperties
) : CloudCommonWebSecurityConfig(authServerClient, securityProperties) {

    @Bean
    override fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    // Legacy read endpoints — no auth (backward compat)
                    .requestMatchers("/rest/api/1/**").permitAll()
                    .requestMatchers("/rest/api/2/**").permitAll()
                    .requestMatchers("/rest/api/3/**").permitAll()
                    // v4 read endpoints — public; anonymous users have ROLE_ANONYMOUS
                    // → ACCESS_COMPONENTS in the role map, so @PreAuthorize passes too.
                    .requestMatchers(HttpMethod.GET, "/rest/api/4/components/**", "/rest/api/4/config/**").permitAll()
                    // v4 writes + admin + audit — require authentication + @PreAuthorize.
                    .requestMatchers("/rest/api/4/**").authenticated()
                    // Actuator, swagger — allowed (from parent)
                    .requestMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
            .csrf { it.disable() }
        return http.build()
    }
}
```

#### PermissionEvaluator
```kotlin
@Component
class PermissionEvaluator(
    securityService: SecurityService
) : BasePermissionEvaluator(securityService) {

    fun hasPermission(permission: String): Boolean =
        super.hasPermission(permission)

    fun canEditComponent(componentName: String): Boolean =
        hasPermission("EDIT_COMPONENTS")
        // Future: component-level ownership check

    fun canDeleteComponent(componentName: String): Boolean =
        hasPermission("DELETE_COMPONENTS")

    fun canArchiveComponent(componentName: String): Boolean =
        hasPermission("ARCHIVE_COMPONENTS")
        // Future: per-component check against componentOwner / releaseManager

    fun canRenameComponent(componentName: String): Boolean =
        hasPermission("RENAME_COMPONENTS")

    fun canImport(): Boolean =
        hasPermission("IMPORT_DATA")
}
```

#### Controller usage
```kotlin
@RestController
@RequestMapping("/rest/api/4")
class ComponentCrudController(private val service: ComponentManagementService) {

    @PreAuthorize("@permissionEvaluator.canEditComponent(#componentName)")
    @PutMapping("/components/{componentName}")
    fun updateComponent(@PathVariable componentName: String, @RequestBody dto: ComponentDto) = ...

    @PreAuthorize("@permissionEvaluator.canDeleteComponent(#componentName)")
    @DeleteMapping("/components/{componentName}")
    fun deleteComponent(@PathVariable componentName: String) = ...

    @PreAuthorize("@permissionEvaluator.canImport()")
    @PostMapping("/admin/import")
    fun importFromDsl() = ...
}
```

### Roles & Permissions

Permissions (7):

| Permission | Endpoint / Operation |
|---|---|
| `ACCESS_COMPONENTS` | read v1–v4 (public; `ROLE_ANONYMOUS` gets it so `@PreAuthorize` on GET methods also passes without auth) |
| `EDIT_COMPONENTS` | `POST /rest/api/4/components`, `PATCH /{id}` (regular attributes), field-overrides CRUD |
| `ARCHIVE_COMPONENTS` | set `archived` via `PATCH /{id}` (future: dedicated `POST /{id}/archive`). Reserved — may move to per-component check (componentOwner, releaseManager) |
| `RENAME_COMPONENTS` | change `name` via `PATCH /{id}` (future: dedicated `POST /{id}/rename`). Impactful — breaks legacy-resolve consumers |
| `DELETE_COMPONENTS` | `DELETE /{id}` — hard delete, ADMIN-only |
| `IMPORT_DATA` | `POST /rest/api/4/admin/**`, `PUT /rest/api/4/admin/config/**` (migrate/import/global-config writes) |
| `ACCESS_AUDIT` | `GET /rest/api/4/audit/**` |

Role map (`octopus-security.roles` in `components-registry-service.yml`):

```yaml
octopus-security:
  roles:
    # Reads on v4 are public at the filter-chain level; ROLE_ANONYMOUS carries
    # ACCESS_COMPONENTS so @PreAuthorize on GET methods also passes without auth.
    # Removing this entry is how we'd later close anonymous reads.
    ROLE_ANONYMOUS:
      - ACCESS_COMPONENTS
    ROLE_REGISTRY_VIEWER:
      - ACCESS_COMPONENTS
      - ACCESS_AUDIT
    ROLE_REGISTRY_EDITOR:
      - ACCESS_COMPONENTS
      - EDIT_COMPONENTS
      - ACCESS_AUDIT
    # Super-admin — reuses the existing Keycloak realm-role `ADMIN`
    # (bare; converter prefixes ROLE_). F1_ADMIN is a separate f1-security legacy
    # role, currently assigned to no one in f1-qa — not used by registry.
    ROLE_ADMIN:
      - ACCESS_COMPONENTS
      - EDIT_COMPONENTS
      - ARCHIVE_COMPONENTS
      - RENAME_COMPONENTS
      - DELETE_COMPONENTS
      - IMPORT_DATA
      - ACCESS_AUDIT
```

Notes on the model:

- `ROLE_REGISTRY_EDITOR` deliberately does **not** carry `ARCHIVE_COMPONENTS` or `RENAME_COMPONENTS`. Archive/rename are reserved for ADMIN (or, longer-term, for a per-component check against `componentOwner` / `releaseManager` — the permission name is kept stable so that check can be added without re-wiring the role map).
- The `PATCH /{id}` SpEL guard enforces this field-by-field: `... and (#request.archived == null or canArchiveComponent(...)) and (#request.name == null or canRenameComponent(...))`. Plain edits stay on `EDIT_COMPONENTS`; archive/rename payloads fail closed with 403 for anyone without the extra permission.
- Super-admin role is `ROLE_ADMIN`, reusing the existing Keycloak `ADMIN` realm-role. This diverges from the original ADR draft (`ROLE_F1_ADMIN`) because `F1_ADMIN` in `f1-qa` is currently assigned to no users — wiring admin-access through it would leave real operators locked out.

### Keycloak Configuration

```yaml
# application.yml
auth-server:
  url: https://<sso-url>
  realm: f1

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/certs
          issuer-uri: ${auth-server.url}/realms/${auth-server.realm}
```

### UI Authentication Flow

If UI is a **separate SPA** (see [ADR-009](009-ui-repository-strategy.md)):
- Option 1: **Keycloak JS Adapter** in SPA → direct Authorization Code + PKCE flow → SPA holds tokens
- Option 2: **BFF pattern** (like dms-ui) → Spring Cloud Gateway + OAuth2 Client + TokenRelay

If UI is **embedded in backend** (like dms-ui):
- Backend acts as BFF with `oauth2Login()` + `TokenRelay` + Spring Cloud Gateway

### Backward Compatibility Strategy

```
Phase 1: Deploy with auth (CURRENT DECISION)
         v1/v2/v3 → permitAll() (Feign clients don't send JWT)
         v4       → authenticated() + @PreAuthorize
         All authenticated users have read access (no special role needed)

Phase 2: Migrate consumers to pass JWT
         Update Feign clients to use service-to-service credentials (client_credentials grant)
         v1/v2/v3 → permitAll(), but log JWT presence for auditing

Phase 3: Full auth on reads
         v1/v2/v3 → authenticated() (after ALL consumers updated and tested)
         Any authenticated user can read (no role restriction)
         Write operations still require ROLE_REGISTRY_EDITOR / ROLE_ADMIN
```

Phase 3 requires coordinated update of 7+ consumer services. Timeline TBD after Phase 1 is stable.

## Consequences

### Positive
- Proven pattern running in production (DMS)
- Shared library reduces code — ~50 lines of config total
- Consistent security model across octopus services
- Keycloak admin console for user/role management (no code changes to add/modify roles)
- SSO: users logged into DMS-UI or API-Gateway are automatically authenticated
- API-Gateway already handles BasicAuth→JWT conversion for CLI/script consumers

### Negative
- Dependency on octopus-cloud-commons library version
- v1/v2/v3 must stay `permitAll()` to avoid breaking 7+ consumer services
- `UserInfoGrantedAuthoritiesConverter` calls Keycloak userinfo on every request (cacheable)

### Risks
- Cloud-commons library update may introduce breaking changes → pin version, test in CI
- Keycloak userinfo call latency → mitigate with local caching of user roles
- Keycloak downtime → v4 writes unavailable, v1/v2/v3 reads unaffected (permitAll)

## References
- [octopus-api-gateway PR#28](https://github.com/octopusden/octopus-api-gateway/pull/28) — security architecture documentation (draft)
- `octopus-cloud-commons:octopus-security-common` — shared security library
- [DMS WebSecurityConfig](https://github.com/octopusden/octopus-dms-service) — `server/.../configuration/WebSecurityConfig.kt`
- [DMS PermissionEvaluator](https://github.com/octopusden/octopus-dms-service) — `server/.../security/PermissionEvaluator.kt`
- API-Gateway routes: `api-gateway-cloud-prod.yaml` — `Path=/components-registry-service/**` with `StripPrefix=1`
