# ADR-007: Dual-Read Migration Strategy

## Status
Accepted

## Context

Migrating from Git-based storage to PostgreSQL is a high-risk change. The `ComponentRegistryResolver` interface is the single point through which all 34 REST endpoints access component data. We need a safe migration path that allows:
- Gradual rollout
- Validation that DB returns the same data as Git
- Instant rollback if issues are found

## Considered Options

### Option A: Big-bang cutover
- Switch from Git to DB in a single deployment
- Simple but risky: no validation period, no rollback

### Option B: Dual-read with feature flag (recommended)
- Implement `DatabaseComponentRegistryResolver` alongside existing `GitComponentRegistryResolver`
- Feature flag `registry.storage=git|db|dual` controls which is active
- In `dual` mode: read from both, compare results, log discrepancies, serve Git response
- Gradually switch to `db` after validation period

### Option C: Shadow traffic
- Route production reads to Git, duplicate to DB in background
- Compare results asynchronously
- More complex infrastructure

## Decision

**Option B: Dual-read with feature flag.**

### Implementation

```
registry.storage=git    → GitComponentRegistryResolver (current, default)
registry.storage=db     → DatabaseComponentRegistryResolver (new)
registry.storage=dual   → DualComponentRegistryResolver (reads both, compares, logs)
```

### DualComponentRegistryResolver behavior
1. Calls `GitComponentRegistryResolver.getComponentById(id)`
2. Calls `DatabaseComponentRegistryResolver.getComponentById(id)`
3. Compares results (deep equals on DTO level)
4. Logs discrepancies as WARN with full diff
5. Returns Git result (trusted source during validation)
6. Metrics: counter of matches vs mismatches

### Migration sequence
```
Phase 1: registry.storage=git       (current state)
Phase 2: registry.storage=routing   (new components → DB, existing → Git)
Phase 3: Gradually import existing   (per-component import + validation)
Phase 4: registry.storage=db        (all components in DB)
Phase 5: Remove Git code             (cleanup)
```

> **Note:** Phase 2-3 use the Component-Level Routing strategy (see [ADR-008](008-component-level-routing.md)) — a refinement where new components go directly to DB while existing components stay in Git until individually migrated and validated. The `dual` mode remains available as a validation tool during per-component import.

### Rollback
At any phase, setting `registry.storage=git` instantly reverts to Git-based storage. No data migration needed for rollback since Git repo remains unchanged.

## Consequences

### Positive
- Zero-risk validation: can run dual-read for days/weeks
- Discrepancy detection catches import bugs or missing data
- Instant rollback via config change
- No downtime during migration
- Metrics provide confidence for cutover decision

### Negative
- Dual-read doubles the read load during validation phase
- `DualComponentRegistryResolver` is temporary code (removed after cutover)
- Deep comparison logic needs to handle acceptable differences (e.g., field ordering)

### Risks
- Performance impact during dual-read → mitigate with async comparison or sampling
- Long-running dual-read phase delays full migration → set time-boxed validation window

## References
- `ComponentRegistryResolver` interface: `component-resolver-api/.../ComponentRegistryResolver`
- Feature flag pattern: Spring `@ConditionalOnProperty` or `@Profile`
