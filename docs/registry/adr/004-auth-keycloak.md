# ADR-004: Authentication & Authorization via Keycloak

## Status
Accepted. **Implemented:** 2026‑04‑28 (commit `b97fad2`, PR #150 — Keycloak auth + v4 `@PreAuthorize`). **Realm-roles `COMPONENTS_REGISTRY_EDITOR` / `_VIEWER` materialised:** 2026‑05‑08 — operator-facing provisioning steps in [`deployment/keycloak-setup.md`](../deployment/keycloak-setup.md).

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
    securityService: SecurityService,
    // Injected as ObjectProvider, NOT a hard dependency: in the `no-db` boot mode
    // (SYS-047) there is no JPA ComponentRepository bean, yet this evaluator must still
    // construct because the git read controllers reference `@permissionEvaluator`.
    componentRepositoryProvider: ObjectProvider<ComponentRepository>,
) : BasePermissionEvaluator(securityService) {

    private val componentRepository: ComponentRepository? by lazy { componentRepositoryProvider.getIfAvailable() }

    fun hasPermission(permission: String): Boolean =
        super.hasPermission(permission)

    // Per-component ownership gate (IMPLEMENTED — the "component-level ownership
    // check" this section originally reserved). Effective predicate:
    // ACCESS_COMPONENTS && (componentOwner || releaseManager || securityChampion ||
    // managerOfOwner (SYS-063) || EDIT_ANY_COMPONENT). `componentIdOrName` may be a
    // UUID (the live v4 call site) or a component key. Usernames are matched
    // trimmed + case-insensitively; a component with no owner AND no RM AND no SC is
    // admin-only; an unresolvable id/key denies (→ 403, since @PreAuthorize runs
    // before the controller). Owner/RM/SC are read via scalar projection queries so
    // the LAZY child collections are never touched outside a Hibernate session.
    // managerOfOwner (the owner's manager, per employee-service) checks last since
    // it is the only condition requiring a network call; any resolution failure
    // denies (fail-closed).
    fun canEditComponent(componentIdOrName: String): Boolean {
        if (!hasPermission("ACCESS_COMPONENTS")) return false
        if (hasPermission("EDIT_ANY_COMPONENT")) return true
        val username = securityService.getCurrentUser().username
        // resolve UUID-or-key, then match `username` against owner / RM / SC …
    }

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

Permissions (9):

| Permission | Endpoint / Operation |
|---|---|
| `ACCESS_COMPONENTS` | read v1–v4 (public; `ROLE_ANONYMOUS` gets it so `@PreAuthorize` on GET methods also passes without auth) |
| `CREATE_COMPONENTS` | `POST /rest/api/4/components` (create — no owner exists yet, so this is the sole gate). Not required for component-scoped edit after creation; `PATCH /{id}` and field-overrides CRUD use `canEditComponent` instead |
| `EDIT_ANY_COMPONENT` | bypass the per-component owner/RM/SC/manager-of-owner edit check — edit ANY component when combined with `ACCESS_COMPONENTS` (e.g. to reassign a departed owner). `ROLE_ADMIN` only |
| `ARCHIVE_COMPONENTS` | set `archived` via `PATCH /{id}` (future: dedicated `POST /{id}/archive`). Reserved — may move to per-component check (componentOwner, releaseManager) |
| `RENAME_COMPONENTS` | change `name` via `PATCH /{id}` (future: dedicated `POST /{id}/rename`). Impactful — breaks legacy-resolve consumers |
| `DELETE_COMPONENTS` | `DELETE /{id}` — hard delete, ADMIN-only |
| `IMPORT_DATA` | `POST /rest/api/4/admin/**`, `PUT /rest/api/4/admin/config/**` (migrate/import/global-config writes) |
| `ACCESS_AUDIT` | `GET /rest/api/4/audit/**` |
| `EDIT_METADATA` | edit component-configuration metadata — gates the Portal Field-Overrides edit surface (add/edit/delete, incl. marker editing). `ROLE_ADMIN` only. Currently enforced client-side in the Portal; the permission is surfaced via `/auth/me` |

Role map (`octopus-security.roles` in `components-registry-service.yml`):

```yaml
octopus-security:
  roles:
    # Reads on v4 are public at the filter-chain level; ROLE_ANONYMOUS carries
    # ACCESS_COMPONENTS so @PreAuthorize on GET methods also passes without auth.
    # Removing this entry is how we'd later close anonymous reads.
    ROLE_ANONYMOUS:
      - ACCESS_COMPONENTS
    # Naming: the short `REGISTRY_*` was ambiguous (Docker/npm/CRS); switched
    # to `COMPONENTS_REGISTRY_*` to mirror the service name, by analogy with
    # `EMPLOYEE_SERVICE_USER`.
    ROLE_COMPONENTS_REGISTRY_VIEWER:
      - ACCESS_COMPONENTS
      - ACCESS_AUDIT
    ROLE_COMPONENTS_REGISTRY_EDITOR:
      - ACCESS_COMPONENTS
      - CREATE_COMPONENTS
      - ACCESS_AUDIT
    # Super-admin — reuses whatever platform-wide admin realm-role already
    # exists in your Keycloak realm (commonly literally named `ADMIN`; the
    # converter prefixes `ROLE_`). Earlier drafts proposed a dedicated
    # `ROLE_<PRODUCT>_ADMIN`; reusing the platform admin avoids wiring
    # admin-access through a role nobody currently carries.
    ROLE_ADMIN:
      - ACCESS_COMPONENTS
      - CREATE_COMPONENTS
      - EDIT_ANY_COMPONENT
      - ARCHIVE_COMPONENTS
      - RENAME_COMPONENTS
      - DELETE_COMPONENTS
      - IMPORT_DATA
      - ACCESS_AUDIT
      - EDIT_METADATA
```

Notes on the model:

- **Per-component edit ownership (implemented).** `PATCH /{id}` and all field-override CRUD (`POST`/`PATCH`/`DELETE /{id}/field-overrides`) are gated by `canEditComponent`: the effective predicate is `ACCESS_COMPONENTS && (componentOwner || releaseManager || securityChampion || managerOfOwner || EDIT_ANY_COMPONENT)`. Matching is trimmed + case-insensitive against the JWT `preferred_username`. A legacy component with no owner AND empty RM AND empty SC passes the security gate only for `EDIT_ANY_COMPONENT` holders; because `componentOwner` is required in the final state, an admin PATCH must assign one. An unresolvable id/key denies, so a `PATCH` of a non-existent component returns **403, not 404** (the `@PreAuthorize` gate runs before the controller). `createComponent` stays on bare `CREATE_COMPONENTS` (no persisted component exists yet for an ownership check). The detail response (`GET`/create/`PATCH`) carries a per-caller `canEdit` boolean computed from the same predicate so the Portal can hide the edit affordances.
- **Manager-of-owner grant (SYS-063, implemented).** `managerOfOwner` resolves the componentOwner's manager via `EmployeeDirectoryService.getManager` (employee-service) and checks last, since it's the only condition needing a network call. Any resolution failure (no manager, owner not found, employee-service unavailable) denies — fail-closed, a directory outage can only shrink this grant, never widen it. Derived only: `GET /{idOrName}/editors` still lists just the explicit owner/RM/SC, not the owner's manager.
- `ROLE_COMPONENTS_REGISTRY_EDITOR` deliberately does **not** carry `ARCHIVE_COMPONENTS` or `RENAME_COMPONENTS`. Archive/rename are reserved for ADMIN (or, longer-term, for a per-component check against `componentOwner` / `releaseManager` — the permission name is kept stable so that check can be added without re-wiring the role map).
- The `PATCH /{id}` SpEL guard enforces this field-by-field: `... canEditComponent(...) and (#request.archived == null or canArchiveComponent(...)) and (#request.name == null or canRenameComponent(...))`. Plain edits use the ownership/admin predicate; archive/rename payloads fail closed with 403 for anyone without the extra permission.
- Super-admin role is `ROLE_ADMIN`, reusing whatever existing platform-wide admin realm-role your Keycloak instance already carries (commonly literally named `ADMIN`). This diverges from an earlier draft that proposed a dedicated `ROLE_<PRODUCT>_ADMIN`; reusing the platform admin avoids wiring admin-access through a role nobody currently carries.

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
         Write operations still require ROLE_COMPONENTS_REGISTRY_EDITOR / ROLE_ADMIN
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
