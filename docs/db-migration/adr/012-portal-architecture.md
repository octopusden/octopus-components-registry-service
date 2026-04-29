# ADR-012: Portal Architecture — Separate Spring Cloud Gateway Repository

## Status
Accepted. **Implemented:** 2026‑04‑14 (commit `26278f2`, PR #147 — UI extracted from this repo).

## Context

[ADR-009](009-ui-repository-strategy.md) recommended **Option B-2** — keeping the React UI as a Gradle module inside this repository and embedding the built assets into the Spring Boot JAR (single Pod, single image, single OKD Template). That recommendation was not implemented.

In April 2026 the team chose **Option A** (separate repository) instead. The UI now lives in [`octopus-components-management-portal`](https://github.com/octopusden/octopus-components-management-portal), a standalone Spring Cloud Gateway (WebFlux) application that hosts the React+Vite frontend and proxies API calls to this service. The embedded UI module (`components-registry-ui/`) and `SpaWebConfig.kt` were deleted from this repo by PR #147.

This ADR captures the architecture that actually shipped, supersedes ADR-009, and pins down the contract between the two repositories so the boundary is reviewable.

### Why Option B-2 was reversed

ADR-009 itself does not record a re-evaluation. Practical drivers for the change visible from the result:
- **OAuth2 BFF separation.** Hosting the BFF (browser session, OIDC redirect dance, TokenRelay) inside the registry service mixes two concerns that are easier to reason about as separate Spring contexts — particularly when v1/v2/v3 must keep accepting unauthenticated/Basic-Auth Feign traffic from 7+ legacy consumers.
- **Frontend tooling friction.** Vite + Gradle `node-gradle` integration imposed a non-trivial cost on UI iteration speed; a standalone repo lets the frontend ship with `npm run dev` + Vite proxy without any Gradle in the developer's path.
- **Independent rollouts.** Frontend changes deploy without rebuilding the registry service JAR.

These trade-offs are accepted; their costs are mitigated by TD-003 (drop legacy `columnDefinition` workarounds) and TD-004 (OpenAPI generation to keep frontend types in sync without a monorepo).

## Decision

Two repositories, two deploy units:

```
┌──────────────────────────┐          ┌────────────────────────────────┐
│  Browser (React SPA)     │          │  Direct Feign / CLI consumers  │
│  served by Portal        │          │  (dms-service, jira-utils, …)  │
└────────────┬─────────────┘          └──────────────┬─────────────────┘
             │ same-origin: /rest/**, /auth/**       │ Bearer JWT (or anon for v1/v2/v3)
             ▼                                       │
┌─────────────────────────────────────────────┐      │
│  octopus-components-management-portal       │      │
│  Spring Cloud Gateway (WebFlux)             │      │
│  - OAuth2 Login + OIDC code flow            │      │
│  - Server-side session = browser auth       │      │
│  - TokenRelay → Authorization: Bearer       │      │
│  - SpaFallbackFilter → React index.html     │      │
│  - PortalInfoController → /portal/info      │      │
└────────────────────┬────────────────────────┘      │
                     │ Authorization: Bearer <jwt>   │
                     ▼                               ▼
┌────────────────────────────────────────────────────────────┐
│  components-registry-service (this repo)                    │
│  - WebSecurityConfig: OAuth2 Resource Server (JWT)          │
│  - v1/v2/v3 → permitAll() (legacy Feign clients)            │
│  - v4 reads (GET /components, /config) → permitAll          │
│  - v4 writes + admin + audit → @PreAuthorize                │
│  - /rest/api/4/info → permitAll (footer build label)        │
│  - /auth/me → authenticated (current user)                  │
└────────────────────────────────────────────────────────────┘
```

### Repository responsibilities

**Portal** (`octopus-components-management-portal`):
- Browser authentication (OIDC authorization code flow against Keycloak).
- Server-side session as browser auth state; TokenRelay forwards `Bearer <access_token>` on proxied calls.
- Routes:
  - `/rest/**` → `components-registry-service` (with TokenRelay).
  - `/auth/**` → `components-registry-service` (with TokenRelay; covers `/auth/me`).
  - `/portal/info` → handled locally by `PortalInfoController` (anonymous, exposes Portal build info).
  - `/oauth2/**`, `/login/**`, `/logout/**` → Spring Security OAuth2 (login/logout dance).
  - Everything else not matching API/auth/asset prefixes → `index.html` via `SpaFallbackFilter` for client-side React routing.
- CSRF: cookie-based double-submit using `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` plus the **plain** `ServerCsrfTokenRequestAttributeHandler` (not the XOR/BREACH-mitigation variant). The frontend reads `XSRF-TOKEN` from the cookie raw and echoes it in `X-XSRF-TOKEN`; the XOR variant would force a 403 on every non-safe request because cookie and header would no longer match. See `SecurityConfig.kt` lines 96–107 for the inline rationale.
- Auth entry point split:
  - `/rest/**` and `/auth/**` → `HttpStatusServerEntryPoint(401)` so the SPA's `api.ts` 401-handler fires cleanly.
  - Anything else → `RedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak")` for browser navigations.
- Anonymous endpoints (`permitAll`): `/assets/**`, `/favicon.ico`, `/vite.svg`, `/actuator/health` (and sub-paths), `/logout/connect/back-channel/**`, `/portal/info`, **`/rest/api/4/info`** — the footer must render before login.

**Registry service** (this repo):
- OAuth2 Resource Server (JWT validation against Keycloak via `octopus-cloud-commons`).
- Sees only proxied requests from Portal (with JWT) and direct Feign/CLI consumers (with or without JWT, depending on consumer).
- Owns all data access; Portal is stateless beyond session.
- v4 reads (`GET /components`, `/config`) are `permitAll()` so Portal can serve the SPA shell to anonymous browsers; v4 writes/admin/audit require `@PreAuthorize`.
- `/rest/api/4/info` is anonymous on the registry side too (matched by Portal as `permitAll` and on this side declared in `WebSecurityConfig`) so the Portal footer can fetch it without the gateway intercepting with a 401.

### Boundary contract

| Direction | Endpoint prefixes | Notes |
|---|---|---|
| Portal → CRS (proxied) | `/rest/**`, `/auth/**` | TokenRelay forwards `Authorization: Bearer …` from session. |
| Portal → CRS (anonymous) | `/rest/api/4/info` | Permit-all on both sides. |
| CRS-only public reads | `/rest/api/{1,2,3}/**`, `GET /rest/api/4/components/**`, `GET /rest/api/4/config/**` | Direct Feign clients still reach these without going through Portal. |
| Browser-only | `/oauth2/**`, `/login/**`, `/logout/**`, `/assets/**`, `/portal/info` | Handled inside Portal; never proxied. |

## Consequences

### Positive
- BFF responsibilities (OIDC, session, CSRF) live in one focused codebase; this repo stays a pure resource server.
- Frontend iteration is decoupled from Gradle/Spring builds.
- OKD topology stays simple: two Templates, two Deployments. Same Keycloak realm.
- Anonymous footer endpoints are explicit on both sides; no implicit gateway-only behavior.

### Negative — and how mitigated
- **API + UI changes are no longer atomic.** Mitigated by TD-004 (OpenAPI v4 spec generation, share with Portal).
- **CSRF policy is unusual.** Plain (non-XOR) double-submit is intentional and documented in `SecurityConfig.kt` lines 96–107; reviewers must not "fix" it back to the default XOR handler.
- **Migration job state lives in the registry service in-memory.** When the registry pod restarts mid-migration, the Portal's MigrationPanel gets `404` on the polling endpoint. Closed long-term by [MIG-028](../requirements-migration.md) (persisted job state).
- **Two deploy units to coordinate.** TLS/cert rotation, Vault secrets, and Spring Cloud Config payloads must be aligned across repos (see Portal `docs/onboarding/components-management-portal.md`).

### Risks
- SPA fallback exclusion list in Portal (`SpaFallbackFilter.kt`) is the source of truth for "what is a backend route vs an SPA route". Adding new top-level backend prefixes requires updating that list — easy to miss in review. Keep the list reviewed alongside any new public endpoint added in either repo.
- Portal session is in-memory by default; pod restart logs all browser users out. See Portal's `TD-003` (persisted session store).

## References
- ADR-003 — UI stack (React 19 + Vite + shadcn/ui).
- ADR-004 — Keycloak auth (this side of the boundary).
- ADR-009 — UI repo strategy (Superseded by this ADR).
- Commit `26278f2` (PR #147) — UI extraction commit in this repo.
- Portal repo: `src/main/kotlin/.../configuration/SecurityConfig.kt`, `SpaFallbackFilter.kt`, `controller/PortalInfoController.kt`.
- Portal local ADR mirror: `docs/adr/001-spring-cloud-gateway-bff.md` (short Portal-side summary, this CRS ADR remains canonical).
