# ADR-006: API Versioning — New v4 for CRUD, Backward Compatible v1/v2/v3

## Status
Accepted

## Context

The service exposes 34 REST endpoints across API v1, v2, and v3. These are consumed by 7+ octopus services via a Feign client (`ComponentsRegistryServiceClient`, 28 methods). We need to add CRUD operations without breaking existing consumers.

### Current API usage analysis (by consumer code inspection)

| API Version | Endpoints | Active Consumers | Status |
|-------------|-----------|-----------------|--------|
| v1 | 4 | 2 (DMS, RMS) — only `getById()` and `getComponentDistribution()` | Legacy, minimal use |
| v2 | 22 | 7+ (all consumers) — core API | **Critical**, heavily used |
| v3 | 4 | 3 (DMS, build-integration, rm-plugin) | Growing, `docker-images` and `copyright` possibly unused |

> **Note**: Runtime access logs should be checked to confirm which v1/v3 endpoints are actually called in production.

## Considered Options

### Option A: Add CRUD to existing v3
- Extend v3 controllers with POST/PUT/DELETE
- Risk: may break existing contract expectations

### Option B: New v4 API (recommended)
- Clean separation: v1/v2/v3 = read-only (unchanged), v4 = CRUD + audit + admin
- No risk to existing consumers
- v4 can have its own DTO format optimized for UI/editing

### Option C: Separate service
- New microservice for write operations
- Overkill, adds deployment complexity, eventual consistency issues

## Decision

**Option B: New API v4** as an additive layer.

### v4 Endpoints
```
POST   /rest/api/4/components                              → create component
GET    /rest/api/4/components/{id}                         → get (extended format for UI)
PUT    /rest/api/4/components/{id}                         → update component
DELETE /rest/api/4/components/{id}                         → soft delete (→ archived)

POST   /rest/api/4/components/{id}/versions                → add version range
PUT    /rest/api/4/components/{id}/versions/{versionId}    → update version
DELETE /rest/api/4/components/{id}/versions/{versionId}    → delete version

GET    /rest/api/4/audit/{entityType}/{entityId}           → change history
GET    /rest/api/4/audit/recent                            → recent changes feed

POST   /rest/api/4/admin/import                            → import from DSL
GET    /rest/api/4/admin/export                            → export to JSON
```

### Strategy
1. **v1/v2/v3 controllers** — untouched, only the data source changes (Git → DB) via `ComponentRegistryResolver` interface
2. **v4 controllers** — new Kotlin controllers with `@PreAuthorize`
3. **DTOs** — v1/v2/v3 DTOs unchanged; v4 uses new DTOs optimized for editing (include all nested config in one response)
4. **Feign client** — published as-is, no changes to `ComponentsRegistryServiceClient`

## Consequences

### Positive
- Zero risk to existing consumers
- Clean separation of concerns (read vs write)
- v4 DTOs can be optimized for UI needs (full component tree in one response)
- Can add v4 Feign client separately for programmatic CRUD consumers

### Negative
- Two "views" of the same data (v2/v3 read DTOs vs v4 edit DTOs)
- More code to maintain

## References
- Feign client: `components-registry-service-client/.../ComponentsRegistryServiceClient.kt`
- API consumer analysis: see plan.md "Анализ реального использования endpoints"
