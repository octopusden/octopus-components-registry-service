# ADR-002: Backend Language — Kotlin / Java 21

## Status
Proposed

## Context

New backend code is needed for: JPA entities, Spring Data repositories, CRUD service layer, API v4 controllers, audit interceptors, security configuration. The project currently uses:
- **Kotlin** — server module (`components-registry-service-server`), DSL module, automation, core
- **Java** — API modules (`component-resolver-api`, `components-registry-api`)
- **Groovy** — legacy DSL configuration files (read-only, not new code)

Need to decide the language for new persistence and API code.

## Considered Options

### Option A: Kotlin (align with server module)
- Consistent with existing server code (30+ .kt files)
- Null-safety, data classes, coroutines, extension functions
- Excellent Spring Boot 3.x support
- Team already writes Kotlin in this project
- `octopus-dms-service` also uses Kotlin for server code

### Option B: Java 21 (align with API modules)
- Consistent with API modules (component-resolver-api, components-registry-api)
- Records, sealed classes, pattern matching (modern Java)
- Larger hiring pool, more developers familiar
- No Kotlin compiler dependency
- Simpler build configuration

### Option C: Mixed (Kotlin for server, Java for new API DTOs)
- Each module uses its established language
- New JPA entities in Kotlin (server module), new API DTOs in Java (API modules)
- Pragmatic but adds cognitive load

## Decision

**Option A (Kotlin)** for consistency with the server module where most new code will live. Java 21 is acceptable for new API-level DTOs if they belong in Java API modules.

## Consequences

### If Kotlin
- Consistent server codebase
- Data classes ideal for JPA entities (with `kotlin-jpa` plugin)
- Need `kotlin-allopen` and `kotlin-noarg` plugins for JPA
- Smaller hiring pool vs Java

### If Java 21
- Records for DTOs, but JPA entities need mutable classes
- No Kotlin plugins needed
- Broader developer familiarity

### Groovy
- **NOT used** for any new code. Existing Groovy DSL is read-only legacy.

## References
- Current server module: `components-registry-service-server/src/main/kotlin/`
- Current API modules: `components-registry-api/src/main/java/`
- [Kotlin JPA best practices](https://spring.io/guides/tutorials/spring-boot-kotlin)
