# ADR-008: Component-Level Routing (Canary Migration)

## Status
Proposed

## Context

ADR-007 describes a dual-read strategy where *all* components are read from both Git and DB, results compared, and Git result served during validation. This is safe but creates a binary cutover moment: eventually, all components switch from Git to DB at once.

A more graceful approach is to route reads per component based on its origin:
- **New components** (created via API v4 / UI after migration starts) → DB-only, never exist in Git
- **Existing components** (loaded from Groovy/Kotlin DSL) → continue reading from Git until individually imported and validated in DB

This provides real production canary testing: new components exercise the full DB-backed code path (write, read, audit, search) under real traffic while existing components remain safely in Git.

## Decision

**Add a component-level routing phase to the migration sequence.**

### Routing Logic

```
ComponentRoutingResolver implements ComponentRegistryResolver {

    GitComponentRegistryResolver gitResolver
    DatabaseComponentRegistryResolver dbResolver
    ComponentSourceRegistry sourceRegistry   // tracks which source owns each component

    getComponentById(id):
        if sourceRegistry.isDbComponent(id):
            return dbResolver.getComponentById(id)
        else:
            return gitResolver.getComponentById(id)

    getAllComponents():
        gitComponents  = gitResolver.getAllComponents()
        dbComponents   = dbResolver.getAllComponents()
        return merge(gitComponents, dbComponents)
}
```

### Component Source Registry

Simple table or in-memory map tracking component origin:

```sql
CREATE TABLE component_source (
    component_name  VARCHAR(255) PRIMARY KEY,
    source          VARCHAR(10) NOT NULL DEFAULT 'git',  -- 'git' | 'db'
    migrated_at     TIMESTAMP,
    migrated_by     VARCHAR(255)
);

-- New component created via API v4 → source = 'db'
-- Existing component after import + validation → source updated to 'db'
```

### Updated Migration Sequence

```
Phase 1: registry.storage=git              (current state)
Phase 2: Deploy DB + RoutingResolver        (feature flag: registry.storage=routing)
Phase 3: New components → DB                (created via API v4 / UI only in DB)
         Existing components → Git          (no change for consumers)
Phase 4: Gradually import existing → DB     (per-component, with validation)
         Each import: load DSL → DB → validate → flip source to 'db'
Phase 5: All components in DB               (registry.storage=db)
Phase 6: Remove Git code                    (cleanup)
```

### Feature Flag Extension

```yaml
registry:
  storage: git       # git | db | routing | dual
```

- `routing` — new mode: routes per component based on `component_source` table
- `dual` — kept as optional validation mode (reads both, compares all)

### Merge Strategy for Aggregate Queries

Endpoints like `getAllComponents()`, `findByGroupId()`, search — must merge results from both sources:

```kotlin
override fun getAllComponents(): List<Component> {
    val gitComponents = gitResolver.getAllComponents()
        .filter { sourceRegistry.isGitComponent(it.id) }
    val dbComponents = dbResolver.getAllComponents()
        .filter { sourceRegistry.isDbComponent(it.id) }
    return gitComponents + dbComponents
}
```

### Per-Component Import & Cutover

Admin endpoint for migrating individual components:

```
POST /api/v4/admin/migrate-component/{componentName}

Steps:
1. Load component from Git DSL
2. Write to DB (JPA entities)
3. Compare Git vs DB read results (deep equals)
4. If match → update component_source.source = 'db'
5. If mismatch → log error, keep source = 'git', return discrepancy report
```

Bulk variant for batch migration:
```
POST /api/v4/admin/migrate-components
Body: { "components": ["comp-a", "comp-b", ...] }  // or { "all": true }
```

## Consequences

### Positive
- **Real canary testing**: new components validate the full DB path under production traffic
- **Zero risk to existing data**: old components continue reading from Git until explicitly migrated
- **Gradual migration**: import one component at a time, validate, flip — no big-bang moment
- **Early value**: team starts using UI/API v4 for new components immediately, doesn't wait for full import
- **Per-component rollback**: if a single component has issues in DB, flip it back to `git`

### Negative
- **Merge complexity**: aggregate queries (list all, search, find by group) must merge two sources
- **Consistency edge cases**: cross-component references (dependencies) may span Git and DB
- **Longer migration window**: running two storage backends simultaneously for an extended period
- **Extra table**: `component_source` is migration-only infrastructure (removed after Phase 6)

### Mitigations
- Merge logic centralized in `ComponentRoutingResolver` — one place to test
- Cross-source dependency resolution: resolve dependencies by name, both resolvers return same DTO types
- Set target date for Phase 5 (all in DB) to prevent indefinite dual-running
- `component_source` table is simple and can be dropped after migration

## Interaction with Other ADRs

- **ADR-007 (Dual-Read)**: Routing is a *refinement* of dual-read. Dual-read can still be used as a pre-cutover validation tool for individual components during import
- **ADR-006 (API v4)**: v4 CRUD endpoints create components directly in DB → `component_source.source = 'db'`
- **ADR-005 (Audit Log)**: Only DB-sourced components have audit trail; Git-sourced components retain Git history until migrated
- **ADR-004 (Keycloak Auth)**: Write auth applies to API v4 (DB writes); Git-sourced reads remain unauthenticated

## References
- ADR-007: Dual-Read Migration Strategy
- Canary deployment pattern: https://martinfowler.com/bliki/CanaryRelease.html
- Strangler Fig pattern: https://martinfowler.com/bliki/StranglerFigApplication.html
