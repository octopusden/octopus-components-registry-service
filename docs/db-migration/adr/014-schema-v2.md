# ADR-014: CRS Schema v2 Redesign

**Status:** Proposed
**Supersedes:** ADR-010 (Schema Extensibility — JSONB-based metadata)
**Related:** ADR-001 (Storage: PostgreSQL), ADR-007 (Dual-read migration)
**Detailed reference:** `docs/db-migration/schema-spec.md`

## Context

The current schema (V1..V6) carries several issues uncovered during MIG-029 investigation and a cross-installation DSL audit:

- **Polymorphic FK pairs** (`component_id` + `component_version_id`) on six configuration tables (build, escrow, jira, vcs, distribution, artifact_ids). One must be NULL — fragile and forces awkward queries.
- **JSONB `metadata` columns** on eight tables store known stable fields via Hibernate Map serialization. Audit found zero PostgreSQL-specific JSONB features used (no `@>`, `->`, `?` operators; GIN indexes never hit). Seven of these belong as explicit typed columns.
- **MIG-029**: `EntityMappers.toEscrowModule()` unconditionally synthesises an `(,0),[0,)` config for every component. For DSL components whose definitions consist only of version-range blocks (no top-level fields), this surfaces a spurious row in legacy variants-Map endpoints (`GET /rest/api/3/components`). The `hasDefaultConfig` flag computed in `toComponentEntity()` is never persisted.
- **`text[]`** for `system` and `labels` (PostgreSQL-only; no array containment queries used).
- **`component_versions` table** holds little beyond a range string + JSONB metadata; doesn't earn its existence.
- **No per-attribute version-range model** despite DSL allowing single-attribute overrides per range.
- **Cross-installation assumptions** baked into the schema (e.g., `system = "CLASSIC"` is single-installation; other installations are known to use a different set of system codes).

Project is not yet in production; QA/dev databases are wiped and recreated on each deploy.

## Decision

Replace V1..V6 with a single consolidated `V1__schema.sql` baseline implementing **Model A'** — a wide typed `component_configurations` table with four row shapes (base, scalar override, marker override, range presence) classified by the source-of-truth `row_type` column, plus dictionary tables, child relationships, and an aggregator grouping concept.

Highlights:

1. **No polymorphic FKs.** All per-version data lives on `component_configurations` (single FK to `components`).
2. **No JSONB metadata.** Seven JSONB columns promoted to typed columns; five legitimate JSON values (`audit_log`, `registry_config`) kept as TEXT via Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.
3. **Per-attribute version-range overrides.** Each override is a row keyed by `(component_id, version_range, overridden_attribute)`. Three shapes: NULL (base), `'aspect.field'` (scalar), or marker name (replacement of a child collection).
4. **`is_synthetic_base` flag** on the base row identifies bases populated purely from `Defaults.groovy`. Legacy enumeration endpoints skip synthetic bases — eliminates MIG-029 structurally; hot-path single-version resolve still uses base as fallback.
5. **Unified VCS model.** SINGLE-VCS is the special case "1 entry, name=NULL" in `vcs_settings_entries`. No discriminator; no scalar VCS columns on `component_configurations`.
6. **Distribution split into four typed tables** (Maven coords, file URLs, Docker images, DEB/RPM packages). Per-family `sort_order` preserves DSL CSV order within each family; mapper concatenates families canonically for v1-v3 responses.
7. **Reference dictionaries** for `labels`, `systems`, `tools` (admin-managed). Auto-discovered or seeded from `validation-config.yaml` during migration.
8. **Aggregator grouping** via `component_groups`. Detection at migration: an aggregator is REAL when it has its own valid (non-placeholder) `vcsUrl`; otherwise FAKE. REAL aggregator has its own `components` row + group; FAKE has only the group; sub-components reference the group.
9. **Two FK columns on `components`**: `parent_component_id` (DSL `parentComponent` reference between top-level peers) and `component_group_id` (aggregator membership) — orthogonal.
10. **Targeted DB CHECK constraints** for marker rows (one CHECK per marker name). Full structural validation (scalar override = exactly one non-NULL column) lives in the service layer.
11. **Partial unique index** ensures one base row per component.

Total: 23 tables.

### Migration approach

- No data migration; QA/dev recreate from `V1__schema.sql`.
- `POST /admin/migrate` rewritten to write the new shape from DSL via `EscrowConfigurationLoader.loadFullConfiguration`.
- Two-pass for `parentComponent` resolution (string → FK by `component_key`).
- Two-pass for aggregator groups (1st pass: discover + create groups + REAL aggregator components; 2nd pass: link sub-components).
- Pre-pass discovery for `systems` and `tools` dictionaries (upsert distinct values from DSL).
- `labels` dictionary seeded from installation's `validation-config.yaml`.

### v1-v3 backward compatibility

All v1-v3 endpoints continue to return semantically identical responses, served via a thin mapper layer over the new schema. Five effectively-dead endpoints (≤2 calls/2 prod days) are formalised as dropped or stubbed (kill-list in `schema-spec.md`).

## Consequences

### Positive
- DB-level type safety on every persisted value.
- MIG-029 structurally impossible (no synthesis path in resolver; legacy mapper skips synthetic base).
- Per-attribute override matches DSL grammar and empirical data (≈14% of multi-variant components have aspect-divergent version ranges).
- Schema portable: no JSONB-specific features; one PG-specific feature is `pgcrypto.gen_random_uuid()` (replaceable).
- Reduced row count for static-value components (no need to duplicate constants across N range rows when a single attribute changes).
- Aggregator relationships preserved through migration (currently flattened and lost).
- Cross-installation friendly: dictionaries (`systems`, `tools`) are auto-discovered; `labels` are seeded per-installation from yaml.

### Negative / breaking
- Sparse override rows have many NULL columns (storage: cheap via PG null bitmap; query: trivial).
- Two row shapes in one table (scalar vs marker) require service-layer validation in addition to CHECK constraints.
- Migration code grows ≈2× (per-aspect diff, marker rows, dictionary discovery, distribution family split).
- v4 DTOs change shape (no more generic `metadata: Map<String, Any?>`); v4 client (Portal) must be updated.
- Five v1-v3 endpoints dropped or stubbed (all had ≤2 calls in 2 prod days; full kill-list with HTTP-code mapping in `schema-spec.md`).

## Alternatives Considered

### Model A — single consolidated wide table (full snapshot per range)

One row per (component, version_range), all aspect fields populated each row.

**Rejected.** No per-attribute independent override; heavy duplication for components where only one field changes per range. Real data shows 14% of multi-variant components have aspect-divergent ranges (build changes at one boundary, jira at another, vcs at a third); single shared range-axis cannot model this without lossy collapse.

### Model B — separate aspect tables

`build_configurations`, `escrow_configurations`, `jira_component_configs`, `vcs_settings` — each with own version_range column and typed fields.

**Rejected.** Solves aspect-level independence but not attribute-level. Four tables per resolve; v4 CRUD splits into four resources. Migration must shard a single DSL config across four entity types. Empirically, the dominant override pattern is "one DSL block changes one field" — aspect-level granularity is unnecessarily coarse.

### Model C — full EAV (single attribute-value table)

`component_attribute_values(component_id, attribute_path, version_range, value TEXT/JSON)`. Every value is a row.

**Rejected.** Loses DB-level type safety. Validation moves entirely to application. v4 API model becomes "table of facts" rather than object. Resolve verbose (filter by attribute_path prefix, merge in app). Indexing strategy harder. Flexibility (no DDL for new attributes) is not load-bearing — `requirements-migration.md` history shows fields are added in scheduled batches, not ad-hoc.

### Model D — typed `components` defaults + `field_overrides` for overrides

`components` is the wide table; sparse `field_overrides(field_path, version_range, value JSON)` carry per-range deltas.

**Rejected.** Close to A' in shape but loses type safety on overrides (JSON polymorphic value). For components without top-level DSL fields, `components` defaults are either synthesised or empty — semantically confusing. Two storage layouts to merge at read time.

### Model A' — chosen

Wide typed `component_configurations` with base + sparse single-attribute overrides + marker rows for child-collection replacement.

**Why selected.** Keeps everything typed (defaults AND overrides). Per-attribute independent ranges first-class. One table — simple JPA mapping. Single resolve query returns base + matching overrides; merge per field. Adding new fields is a typed `ALTER TABLE`, acceptable for the historical batch cadence of schema evolution. Marker rows cover child collections cleanly with full-replacement semantics matching DSL grammar.

## Implementation notes

Phases tracked in `implementation-progress.md` under "Schema v2 refactor":

1a. Design artifacts (this ADR, `schema-spec.md`) — **done**.
1b. `V1__schema.sql` baseline (replaces V1..V6) — **pending**, bundled with Phase 2 entity refactor to keep DB and JPA aligned in one merge.
2. Entity refactor (≈16 → ≈22 classes due to dictionaries + distribution split).
3. Mapper / resolver rewrite.
4. v4 DTO update.
5. Migration service rewrite.
6. Test suite (synthetic DSL fixtures + integration tests; real-data baselines kept in internal CI only).
7. QA recreate + full `gradlew build`.
8. ADR updates (this file, supersede 010).

Each phase ends with subagent review (Sonnet by default) per team convention.
