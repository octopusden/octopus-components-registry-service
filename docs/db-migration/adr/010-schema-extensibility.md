# ADR-010: Schema Extensibility — Hybrid Columns + JSONB Strategy

## Status
Proposed

## Context

The Components Registry model has evolved organically. Since 2023, multiple properties were added to the `Component` class: `labels`, `copyright`, `doc`, `archived`, `solution`, `releasesInDefaultBranch`, `securityChampion`, `releaseManager`, `clientCode`, `parentComponent`.

In the Git/DSL model, adding a new property is trivial: add a field to the DSL, whoever wants it — fills it in. No schema migrations, no coordination.

In the proposed PostgreSQL model, every new property requires: Flyway migration → JPA entity change → mapper update → DTO update → possible API change → deployment. This friction will slow down model evolution.

We need a strategy that balances:
- **Type safety** for stable, queryable fields
- **Flexibility** for frequently-added metadata
- **Performance** for search and filtering

## Analysis: Property Stability Tiers

Analyzing the history of `Component.kt` and the domain model, component properties fall into three stability tiers:

### Tier 1 — Stable core (changes: ~never)
Fields that define the component's identity and relationships. Used in JOINs, WHERE clauses, unique constraints.

```
name, archived, parentComponent, productType, system, clientCode, solution
```

### Tier 2 — Domain configurations (changes: rare, when domain model evolves)
Structured sub-objects with their own lifecycle and clear schema. Each has multiple fields and relational integrity.

```
build_configurations, escrow_configurations, vcs_settings,
distributions, jira_component_configs
```

### Tier 3 — Extensible metadata (changes: frequent, driven by team/process needs)
Fields that describe the component but don't affect build/release logic. Often added ad-hoc to support reporting, ownership, or documentation needs.

```
displayName, releaseManager, securityChampion,
copyright, labels, doc, releasesInDefaultBranch,
and future properties
```

## Considered Options

### Option A: All properties as columns
Every property is a dedicated column in the `components` table.

- **Pro:** Full type safety, straightforward SQL queries, standard indexing
- **Con:** Flyway migration for every new field; wide tables with many nullable columns; tight coupling between schema and application code

### Option B: JSONB for all component data
Store the entire component (except `id` and `name`) as a single JSONB column.

- **Pro:** Maximum flexibility, no schema migrations for new fields
- **Con:** No relational integrity for nested configs; poor performance for complex queries; no type safety at DB level; hard to enforce constraints

### Option C: Hybrid — Columns (Tier 1) + Tables (Tier 2) + JSONB (Tier 3)
Stable core fields as columns, domain configs as separate tables (as already designed), extensible metadata in a JSONB column.

- **Pro:** Type safety where it matters; no migrations for metadata changes; JSONB indexable with GIN for search
- **Con:** Two different access patterns (column vs JSONB); mapping logic in service layer

## Decision

**Option C: Hybrid approach.**

### Schema Change

```sql
ALTER TABLE components ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';

-- GIN index for containment queries (@> operator)
CREATE INDEX idx_components_metadata ON components USING GIN (metadata);
```

### Tier Classification

| Tier | Storage | Example fields | Adding new field requires |
|------|---------|----------------|--------------------------|
| 1 — Stable core | Columns | `name`, `component_owner`, `archived`, `system`, `product_type`, `parent_component_id` | Flyway + Entity + Mapper + DTO |
| 2 — Domain configs | Separate tables | build, escrow, VCS, distribution, jira | Flyway + Entity + Mapper + DTO |
| 3 — Extensible metadata | `metadata` JSONB | `releaseManager`, `securityChampion`, `labels`, `doc`, `copyright` | DTO only (optionally: validation schema) |

### JPA Entity

```kotlin
@Entity
@Table(name = "components")
class ComponentEntity(
    // Tier 1 — columns
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    val name: String,

    @Column(name = "component_owner", nullable = false)
    var componentOwner: String,

    var productType: String? = null,
    @Column(columnDefinition = "text[]")
    var system: Set<String> = emptySet(),
    var clientCode: String? = null,

    var archived: Boolean = false,
    var solution: Boolean? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,

    // Tier 3 — JSONB
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),

    // Tier 2 — relations to separate tables
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    val versions: MutableList<ComponentVersionEntity> = mutableListOf(),

    // ...
)
```

### Accessing Tier 3 Fields

```kotlin
// Write
component.metadata["labels"] = listOf("critical", "platform")
component.metadata["doc"] = mapOf("component" to "MY-DOC", "majorVersion" to "2")

// Read (in mapper)
val labels = (component.metadata["labels"] as? List<*>)?.filterIsInstance<String>()?.toSet()
    ?: emptySet()
```

### Validation for Tier 3

JSONB fields lose compile-time type safety. Compensate with:

1. **JSON Schema validation** (optional, for strict environments):
   ```sql
   ALTER TABLE components ADD CONSTRAINT chk_metadata
       CHECK (jsonb_typeof(metadata) = 'object');
   ```

2. **Application-level validation** via a `MetadataValidator` service:
   ```kotlin
   @Component
   class MetadataValidator {
       private val knownFields = mapOf(
           "releaseManager" to String::class,
           "labels" to List::class,
           "doc" to Map::class,
           // new fields registered here — no DB migration needed
       )

       fun validate(metadata: Map<String, Any?>): List<ValidationError> { ... }
   }
   ```

3. **UI schema** — the Web UI reads allowed metadata fields from a configuration endpoint, enabling dynamic form rendering without frontend redeployment.

### Querying Tier 3 Fields

```sql
-- Find components by owner (Tier 1 column — standard indexed lookup)
SELECT * FROM components WHERE component_owner = 'john.doe';

-- Find components with specific label (Tier 3 JSONB)
SELECT * FROM components WHERE metadata @> '{"labels": ["critical"]}';

-- Find components with doc set
SELECT * FROM components WHERE metadata ? 'doc';
```

The GIN index on `metadata` covers containment queries (`@>`, `?`). Equality lookups like `metadata->>'field' = 'value'` require a separate B-tree expression index. Hot-path fields should be promoted to Tier 1 columns (e.g., `componentOwner`) rather than relying on JSONB indexing.

### Migration Path for Existing Fields

When the DB schema is first created, Tier 3 fields are stored in `metadata` from day one. No need to migrate them later from columns to JSONB.

If a Tier 3 field becomes critical for performance or joins (promoted to Tier 1), the migration is:
1. Add column via Flyway
2. Backfill from JSONB: `UPDATE components SET new_col = metadata->>'field'`
3. Update entity and mapper
4. Optionally remove from JSONB (or keep for backward compat)

## Consequences

### Positive
- Adding new metadata fields requires **no Flyway migration** — just update DTO and mapper
- JSONB supports arbitrary nesting (doc, labels, future complex objects)
- GIN index provides efficient search across all metadata fields
- Tier 1 columns retain full relational integrity and performance
- Tier 2 tables retain clear schema for complex domain configs
- Gradual promotion: Tier 3 → Tier 1 if a field becomes critical

### Negative
- Two access patterns: column-based vs JSONB-based — developers need to know which tier a field belongs to
- JSONB fields have weaker type safety — compensated by application-level validation
- Slightly more complex mapper logic (extract from metadata map)
- PostgreSQL-specific (JSONB) — acceptable given ADR-001

### Risks
- Overuse: temptation to put everything in JSONB instead of proper columns → mitigate with tier classification guidelines and code review
- Schema-less drift: metadata accumulates undocumented fields → mitigate with `MetadataValidator` registry and API documentation

## References
- [PostgreSQL JSONB indexing](https://www.postgresql.org/docs/current/datatype-json.html#JSON-INDEXING)
- [ADR-001: PostgreSQL as storage](001-storage-postgresql.md)
- Current `Component.kt` property evolution history
