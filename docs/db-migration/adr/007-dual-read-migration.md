# ADR-007: Component-Source Routing (Migration Strategy)

## Status
Accepted — supersedes previous dual-read / 4-mode design

## Context

Migrating from Git-based storage to PostgreSQL is a high-risk change. The `ComponentRegistryResolver` interface is the single point through which all 34 REST endpoints access component data. We need a safe migration path that allows:
- Gradual per-component rollout
- Validation that DB returns the same data as Git for each component
- No data split within a single component — a component is entirely in Git or entirely in DB

### Design Principle

**A component is atomic with respect to storage.** It is either fully served from Git or fully served from DB. There is no mode where part of a component's data comes from Git and part from DB. This eliminates consistency issues and simplifies reasoning about data ownership.

## Considered Options

### Option A: Big-bang cutover
- Import all components, switch from Git to DB in a single deployment
- Simple but risky: no per-component validation, no gradual rollout

### Option B: Global mode flag (`registry.storage=git|db|routing|dual`)
- 4 global modes controlling entire system behavior
- **Rejected**: overcomplicates the model, mixes concerns (migration phase vs validation tool vs runtime mode), `dual` mode doubles read load for all components, global rollback semantics are misleading after DB-owned writes

### Option C: Per-component source routing (selected)
- Single `component_source` table tracks where each component lives
- `ComponentRoutingResolver` reads the table and delegates to Git or DB resolver per component
- No global mode flag — routing is always active once deployed
- Validation is a step in the import flow, not a system-wide mode

## Decision

**Option C: Per-component source routing via `component_source` table.**

There is no global `registry.storage` flag. The system always uses `ComponentRoutingResolver`, which looks up the source for each component.

### Component Source Table

```sql
CREATE TABLE component_source (
    component_name  VARCHAR(255) PRIMARY KEY,
    source          VARCHAR(10) NOT NULL DEFAULT 'git',  -- 'git' | 'db'
    migrated_at     TIMESTAMP,
    migrated_by     VARCHAR(255)
);
```

- On initial deployment: all existing components have `source = 'git'`
- New components created via API/UI: inserted with `source = 'db'`
- After successful import + validation: updated to `source = 'db'`

### Routing Resolver

```kotlin
@Component
class ComponentRoutingResolver(
    private val gitResolver: GitComponentRegistryResolver,
    private val dbResolver: DatabaseComponentRegistryResolver,
    private val sourceRegistry: ComponentSourceRegistry
) : ComponentRegistryResolver {

    override fun getComponentById(id: String): Component {
        return if (sourceRegistry.isDbComponent(id))
            dbResolver.getComponentById(id)
        else
            gitResolver.getComponentById(id)
    }

    override fun getAllComponents(): List<Component> {
        val gitComponents = gitResolver.getAllComponents()
            .filter { sourceRegistry.isGitComponent(it.id) }
        val dbComponents = dbResolver.getAllComponents()
            .filter { sourceRegistry.isDbComponent(it.id) }
        return gitComponents + dbComponents
    }
}
```

### Migration Lifecycle

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

### Per-Component Import API

```
POST /rest/api/4/admin/migrate-component/{componentName}
POST /rest/api/4/admin/migrate-component/{componentName}?dryRun=true

POST /rest/api/4/admin/migrate-components
Body: { "components": ["comp-a", "comp-b"] }

GET  /rest/api/4/admin/migration-status
Response: { "git": 61, "db": 183, "failed": 3, "dbNative": 15 }
```

### UI Visibility

- **DB-sourced components**: full CRUD in UI (read, edit, create, delete)
- **Git-sourced components**: not visible in UI (available only via legacy v2/v3 API)
- **Backlog**: optional read-only view of Git-sourced components in UI with "Migrate to DB" action (decide after base implementation)

### Validation (replaces `dual` mode)

Validation is **not a system-wide mode** — it is a step within the per-component import flow:

1. Import component X from DSL → DB
2. Call `gitResolver.getComponentById("X")` and `dbResolver.getComponentById("X")`
3. Deep-compare DTOs (handle acceptable differences: field ordering, whitespace)
4. Log result with full diff
5. Only flip `source = 'db'` if validation passes

For bulk validation (CI/testing), a dedicated endpoint can compare all imported components:
```
POST /rest/api/4/admin/validate-migration
Response: { "matched": 180, "mismatched": 3, "details": [...] }
```

## Rollback Semantics

**Rollback is safe only for components that have NOT been modified in DB after import.**

| Component state | Rollback to Git | Data loss? |
|---|---|---|
| `source=git` (never imported) | Already on Git | No |
| `source=db` (imported, not edited) | Flip to `source=git` | No — Git still has the same data |
| `source=db` (edited via API/UI after import) | Flip to `source=git` | **Yes** — DB edits lost, stale Git state served |
| `source=db` (created via API/UI, never in Git) | Cannot rollback | **Component disappears** |

**There is no global "rollback to Git" switch.** Rollback is a per-component decision and is only lossless before DB-owned writes. After the first edit or creation in DB, the migration is a **one-way cutover** for that component.

Git repository is preserved read-only until all components are validated in DB + bake-in period (N days).

## Consequences

### Positive
- Simple mental model: one component = one source, always
- No global mode flag, no 4-mode complexity
- Gradual migration: import and validate one component at a time
- Early value: new components use DB/UI immediately
- Honest rollback semantics: no false promise of instant global revert

### Negative
- Merge complexity for aggregate queries (`getAllComponents`, `findByArtifact`) — must query both sources
- Longer migration window running two storage backends
- `component_source` table is temporary migration infrastructure

### Mitigations
- Merge logic centralized in `ComponentRoutingResolver` — one place to test
- Set target date for Phase 4 to prevent indefinite dual-running
- `component_source` can be dropped after Phase 5

## References
- Strangler Fig pattern: https://martinfowler.com/bliki/StranglerFigApplication.html
- `ComponentRegistryResolver` interface: `component-resolver-api/.../ComponentRegistryResolver`
