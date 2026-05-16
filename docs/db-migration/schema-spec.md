# CRS Schema v2 — Specification

**Status:** Proposed (see [ADR-014](adr/014-schema-v2.md))
**Implementation status:** specified here; the Flyway baseline (`V1__schema.sql`) ships in Phase 1b alongside the entity refactor (see [`implementation-progress.md`](implementation-progress.md)).

This document is the canonical reference for the v2 schema. ADR-014 records the decision and alternatives; this file specifies the exact shape, semantics, and intended usage of every table.

## 1. Overview

23 tables across 7 groups:

| Group | Tables | Count |
|---|---|---:|
| Core | `components`, `component_configurations` | 2 |
| Aggregator grouping | `component_groups` | 1 |
| Dictionaries | `labels`, `systems`, `tools` | 3 |
| M:N junctions | `component_labels`, `component_systems`, `component_required_tools` | 3 |
| Children of `components` | `component_artifact_ids`, `distribution_security_groups`, `component_teamcity_projects`, `component_doc_links` | 4 |
| Children of `component_configurations` | `vcs_settings_entries`, `distribution_maven_artifacts`, `distribution_file_url_artifacts`, `distribution_docker_images`, `distribution_packages`, `component_build_tool_beans` | 6 |
| Cross-cutting | `audit_log`, `registry_config`, `component_source`, `dependency_mappings`, `git_history_import_state` | 5 |

## 2. ER overview

```
                                           ┌─────────────────────┐
                                           │  component_groups   │
                                           │  group_key UNIQUE   │
                                           │  is_fake            │
                                           └──────────┬──────────┘
                                                      │ 1:N (group → its components)
                ┌─────────────────────┐               │
                │     components      ├───────────────┘
                │  component_key PK   │  ← parent_component_id (self-FK, DSL parentComponent)
                │  component_group_id │  ← FK to component_groups; NULL = standalone
                └─────────┬───────────┘
                          │
        ┌──────┬──────┬───┴──┬──────┬──────┬──────┬──────────────┐
        │M:N   │M:N   │1:N   │1:N   │1:N   │1:N   │1:N           │
        ▼      ▼      ▼      ▼      ▼      ▼      ▼              ▼
  component_  component_  component_  distribution_  component_  component_   component_
  labels      systems    artifact_   security_       teamcity_   doc_         configurations
                          ids        groups         projects    links         │
   │          │                                                       ┌───────┼─────────────────┐
   ▼          ▼                                                       │1:N    │1:N each         │M:N
 labels    systems                                                    ▼       ▼                 ▼
                                              vcs_settings_entries  4× distribution_*        component_required_tools
                                                                          (maven, file_url,            │
                                                                           docker, packages)           ▼
                                                                                                    tools

────  cross-cutting  ────
   audit_log     registry_config     component_source     dependency_mappings     git_history_import_state
```

## 3. Core model: Model A' override taxonomy

`component_configurations` holds four row shapes per component. `row_type` is the source-of-truth classifier (DB CHECK `IN ('BASE','SCALAR_OVERRIDE','MARKER','RANGE_PRESENCE')`); `overridden_attribute` is the payload discriminator for SCALAR_OVERRIDE / MARKER and MUST be NULL for BASE / RANGE_PRESENCE. Each row is keyed by `(component_id, version_range, overridden_attribute)` with a UNIQUE constraint. Two partial unique indexes complete the picture: one base row per component, and one RANGE_PRESENCE row per (component, version_range).

### 3.1 Base row
- `row_type = 'BASE'`, `overridden_attribute IS NULL`
- All typed columns may carry values (defaults for that component)
- `is_synthetic_base = true` when populated from `Defaults.groovy` only (DSL has no top-level fields); otherwise `false`

### 3.2 Scalar override row
- `row_type = 'SCALAR_OVERRIDE'`, `overridden_attribute = '<aspect.field>'` (e.g., `build.javaVersion`, `escrow.generation`, `jira.projectKey`)
- The DB CHECK also forbids reusing a marker name as a SCALAR_OVERRIDE attribute path
- **Exactly one typed column non-NULL** — the column matching the attribute path
- No attached child rows
- Service-layer validation enforces single non-NULL; DB CHECK not used for that rule (verbose with 28 columns)

### 3.3 Marker (child-collection replacement) row
- `row_type = 'MARKER'`, `overridden_attribute` is one of the marker names:

| Marker | Replaces |
|---|---|
| `vcs.settings` | `vcs_settings_entries` |
| `distribution.maven` | `distribution_maven_artifacts` |
| `distribution.fileUrl` | `distribution_file_url_artifacts` |
| `distribution.docker` | `distribution_docker_images` |
| `distribution.packages` | `distribution_packages` |
| `build.requiredTools` | `component_required_tools` |
| `build.buildTools` | `component_build_tool_beans` |

- **All typed scalar columns must be NULL** — enforced by a consolidated DB CHECK that covers both MARKER and RANGE_PRESENCE rows
- Child rows attached via FK to this marker row's `id`
- Semantics: full replacement of the corresponding collection for matching version range

### 3.4 Range-presence row
- `row_type = 'RANGE_PRESENCE'`, `overridden_attribute IS NULL`, **all typed scalar columns NULL** (same DB CHECK as MARKER)
- Storage-only enumeration anchor: the DSL declared a `componentVersion("R")` block whose scalars/markers all match base, so neither `emitScalarOverrides` nor `emitMarkerOverrides` produced any row. Without this presence row the range would be invisible to the resolver and disappear from `/jira-component-version-ranges` and `/{component}/maven-artifacts` (RES-001 family symptom).
- Hidden from V4 editor APIs: filtered out in `V4Mappers.toDetailResponse`'s `configurations[]`, never appears in `GET /components/{id}/field-overrides`, and `createFieldOverride` / `updateFieldOverride` / `deleteFieldOverride` reject these rows.
- Resolver enumerates them via the `.distinct()` over `versionRange` so the range surfaces in v1-v3 endpoints; `EntityMappers.resolveForRange` filters them out before applying scalar/marker overrides so no all-NULL row overlays base scalars.
- Partial unique index `uq_component_configurations_one_range_presence` ensures at most one per `(component, version_range)`.

### 3.5 Resolve algorithm

For request `(component, version V)`:

1. Load base row + all matching override rows: `WHERE component_id = X AND (row_type = 'BASE' OR range_contains(version_range, V))`. RANGE_PRESENCE rows are loaded but discarded before scalar/marker overlay.
2. Start with base row scalar values; for each scalar override row matching V, set its single non-NULL column on the merged result.
3. For child collections, pick the most-specific marker override row whose `version_range` contains V; if found, its children REPLACE base children (full replacement). If no marker matches, use base children.
4. Transitional constraint: version_range of override rows within one component MUST NOT partially overlap (equal ranges are blocked by UNIQUE; strict containment and disjoint allowed; partial overlap rejected at write-time). Under this constraint, at most one override per attribute matches V — no runtime tiebreaker needed.
5. For the legacy variants-Map endpoints, skip emitting the base row entry if `is_synthetic_base = true` (this is the MIG-029 fix). For single-version resolve (hot path), synthetic base is always used as fallback.

## 4. Table specifications

### 4.1 `components`

Identity + fields that never vary per version range.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PK | |
| `component_key` | VARCHAR(255) | NOT NULL, UNIQUE | DSL key; exposed as `name` in v1-v3 API, `componentKey` in v4 |
| `component_owner` | VARCHAR(255) | nullable | |
| `display_name` | VARCHAR(255) | nullable | DSL `componentDisplayName` |
| `product_type` | VARCHAR(20) | nullable | App-validated against `ProductTypes` enum (PT_K/PT_C/PT_D_DB/PT_D) |
| `client_code` | VARCHAR(255) | nullable | |
| `archived` | BOOLEAN | NOT NULL DEFAULT false | |
| `solution` | BOOLEAN | nullable | |
| `parent_component_id` | UUID | FK → components(id) | DSL `parentComponent = "X"` reference between peers |
| `component_group_id` | UUID | FK → component_groups(id) | Aggregator membership; NULL for standalone components |
| `release_manager` | VARCHAR(255) | nullable | |
| `security_champion` | VARCHAR(255) | nullable | |
| `copyright` | TEXT | nullable | |
| `releases_in_default_branch` | BOOLEAN | nullable | |
| `jira_display_name` | VARCHAR(255) | nullable | jira.displayName — never varies per version per audit |
| `jira_hotfix_version_format` | VARCHAR(255) | nullable | jira.componentVersionFormat.hotfixVersionFormat — UI read-only (inherited from Defaults) |
| `vcs_external_registry` | TEXT | nullable | vcsSettings.externalRegistry — per-component (audit assertion enforced at migration) |
| `distribution_explicit` | BOOLEAN | nullable | |
| `distribution_external` | BOOLEAN | nullable | |
| `version` | BIGINT | NOT NULL DEFAULT 0 | `@Version` optimistic locking |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMP WITH TIME ZONE | NOT NULL DEFAULT now() | |

Indexes: `archived`, `product_type`, `parent_component_id`, `component_group_id`, partial `component_key WHERE archived = false`.

### 4.2 `component_configurations`

Per-(component, version_range) typed rows; the spine of Model A'.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK | |
| `component_id` | UUID | NOT NULL, FK → components(id) ON DELETE CASCADE | |
| `version_range` | VARCHAR(255) | NOT NULL | DSL range string, e.g., `(,0),[0,)`, `[1.0,2.0)` |
| `overridden_attribute` | VARCHAR(50) | nullable | NULL for BASE/RANGE_PRESENCE; non-NULL for SCALAR_OVERRIDE/MARKER |
| `row_type` | VARCHAR(32) | NOT NULL | `BASE` / `SCALAR_OVERRIDE` / `MARKER` / `RANGE_PRESENCE` — source-of-truth row classifier |
| `is_synthetic_base` | BOOLEAN | NOT NULL DEFAULT false | true only on base rows synthesised from Defaults.groovy |
| Build aspect | | | |
| `build_system` | VARCHAR(50) | | MAVEN/GRADLE/ESCROW_PROVIDED_MANUALLY/PROVIDED/etc. |
| `build_system_version` | VARCHAR(50) | | |
| `java_version` | VARCHAR(50) | | |
| `maven_version` | VARCHAR(50) | | |
| `gradle_version` | VARCHAR(50) | | |
| `build_file_path` | TEXT | | |
| `deprecated` | BOOLEAN | | |
| `required_project` | BOOLEAN | | |
| `project_version` | VARCHAR(255) | | |
| `system_properties` | TEXT | | |
| `build_tasks` | TEXT | | Used for build only |
| `escrow_build_task` | TEXT | | escrow.buildTask — separate from `build_tasks` |
| Escrow aspect | | | |
| `escrow_provided_dependencies` | TEXT | | Comma-separated |
| `escrow_reusable` | BOOLEAN | | |
| `escrow_generation` | VARCHAR(50) | | AUTO/MANUAL/UNSUPPORTED |
| `escrow_disk_space` | VARCHAR(50) | | |
| `escrow_additional_sources` | TEXT | | Comma-separated |
| `escrow_gradle_include_configurations` | TEXT | | |
| `escrow_gradle_exclude_configurations` | TEXT | | |
| `escrow_gradle_include_test_configurations` | BOOLEAN | | |
| Jira aspect | | | |
| `jira_project_key` | VARCHAR(50) | | |
| `jira_technical` | BOOLEAN | | |
| `jira_major_version_format` | VARCHAR(255) | | |
| `jira_release_version_format` | VARCHAR(255) | | |
| `jira_build_version_format` | VARCHAR(255) | | |
| `jira_line_version_format` | VARCHAR(255) | | |
| `jira_version_prefix` | VARCHAR(255) | | jira.customer.versionPrefix |
| `jira_version_format` | VARCHAR(255) | | jira.customer.versionFormat |
| Timestamps | | | |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMP WITH TIME ZONE | NOT NULL DEFAULT now() | |

Constraints:
- `UNIQUE (component_id, version_range, overridden_attribute)` (does NOT enforce single RANGE_PRESENCE per (component, range) — NULLs are distinct under Postgres `NULLS DISTINCT`; the partial index below covers that)
- Partial `UNIQUE INDEX uq_component_configurations_one_base ON component_configurations(component_id) WHERE row_type = 'BASE'`
- Partial `UNIQUE INDEX uq_component_configurations_one_range_presence ON component_configurations(component_id, version_range) WHERE row_type = 'RANGE_PRESENCE'`
- Positive taxonomy CHECK pairing `row_type` with `overridden_attribute` (NULL-safe — written as `A OR B OR C` rather than `NOT X OR y IN (...)` to avoid SQL UNKNOWN-passes-CHECK semantics)
- Consolidated DB CHECK enforcing "all 28 typed scalar cols NULL when `row_type IN ('MARKER','RANGE_PRESENCE')`"

Indexes: `(component_id, version_range)`, `jira_project_key WHERE NOT NULL`, `updated_at`.

VCS fields are absent on this table — see unified VCS model below (`vcs_settings_entries`).

### 4.3 `component_groups`

Aggregator grouping. An aggregator is a DSL component with a nested `components { ... }` block.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `group_key` | VARCHAR(255) | NOT NULL, UNIQUE |
| `is_fake` | BOOLEAN | NOT NULL DEFAULT false |
| `created_at`, `updated_at` | TIMESTAMP WITH TIME ZONE | NOT NULL DEFAULT now() |

Semantics:
- **REAL aggregator** (`is_fake = false`): aggregator is itself a deployable component. Has its own row in `components` whose `component_group_id` points to this group. All sub-components also link to this group.
- **FAKE aggregator** (`is_fake = true`): grouping-only entity. **No row in `components` for the aggregator itself.** Only sub-components link to the group.

Detection rule at migration:

```kotlin
fun isFakeAggregator(cfg: EscrowModuleConfig): Boolean {
    val vcsUrl = cfg.firstVcsUrlAcrossAllForms()    // form 1, 2, or 3
    val artifactId = cfg.artifactIdPattern ?: ""
    return vcsUrl.isNullOrBlank()
        || isFakeVcsUrl(vcsUrl)
        || isFakeArtifactId(artifactId)
}

fun isFakeVcsUrl(url: String) =
    "/fake/" in url || "/dummy/" in url ||
    url.endsWith("fake.git") || url.endsWith("dummy.git") || url.endsWith("stub.git")

fun isFakeArtifactId(aid: String): Boolean {
    val lower = aid.lowercase().trim()
    if (lower in setOf("fake", "dummy", "stub")) return true
    return Regex("(^|-)(fake|dummy|stub)(-|$|,)").containsMatchIn(lower)
}
```

### 4.4 Dictionaries

`labels`, `systems`, `tools` share a common shape: `code/name VARCHAR PK + description TEXT + timestamps`. `tools` additionally has `escrow_env_variable VARCHAR(255), target_location TEXT, source_location TEXT, install_script TEXT`.

Seeding:
- `labels` — from installation's `validation-config.yaml` at first migration.
- `systems` — auto-discovered from DSL `system = "X,Y"` values during migration (UPSERT each distinct token).
- `tools` — auto-discovered from DSL top-level `Tools { name { ... } ... }` block during migration.

### 4.5 M:N junctions

Each junction has composite PK `(component_X_id, target_code)`.

| Junction | Connects | FK targets |
|---|---|---|
| `component_labels` | `components` ↔ `labels` | components(id), labels(code) |
| `component_systems` | `components` ↔ `systems` | components(id), systems(code) |
| `component_required_tools` | `component_configurations` ↔ `tools` | component_configurations(id), tools(name) |

`component_required_tools` is per-version-rangeable; replacement at a specific range uses the `build.requiredTools` marker row.

### 4.6 Children of `components` (never per-version)

| Table | Key fields | Notes |
|---|---|---|
| `component_artifact_ids` | `group_pattern, artifact_pattern` | Used by `find-by-artifact` hot endpoint; indexed `(group_pattern, artifact_pattern)` |
| `distribution_security_groups` | `group_type ∈ {read, write}, group_name` | Audit confirms never per-version |
| `component_teamcity_projects` | `project_id, sort_order` | Multi-id supported |
| `component_doc_links` | `doc_component_key, major_version, sort_order` | Soft-reference; target may be archived or out-of-installation; UNIQUE `(component_id, doc_component_key, major_version)` |

### 4.7 Children of `component_configurations` (per-version-rangeable)

#### Unified VCS — `vcs_settings_entries`

SINGLE-VCS is "1 entry with `name = NULL`"; MULTI-VCS is "N entries with names". No discriminator column.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `component_configuration_id` | UUID FK | |
| `name` | VARCHAR(255), nullable | NULL for single-VCS; named for multi-VCS |
| `vcs_path` | TEXT NOT NULL | |
| `branch` | TEXT | |
| `tag` | TEXT | |
| `hotfix_branch` | TEXT | |
| `repository_type` | VARCHAR(20) | GIT/MERCURIAL/CVS (Java enum); typically GIT |
| `sort_order` | INT NOT NULL DEFAULT 0 | |

`vcs.type` (GIT/EXTERNAL/null) computed at API layer:
- `components.vcs_external_registry IS NOT NULL` → `EXTERNAL`
- else `vcs_settings_entries` count = 0 → `null` (no VCS)
- else → `GIT` (or other repository_type from entries[0])

#### Distribution split (4 specialized tables)

DSL `distribution { GAV = "...", docker = "...", DEB = "...", RPM = "..." }` decomposes into four families. `sort_order` is **per-family** within a `component_configuration_id`.

| Table | Distinct columns |
|---|---|
| `distribution_maven_artifacts` | `group_pattern, artifact_pattern, extension, classifier` |
| `distribution_file_url_artifacts` | `url, artifact_id, classifier` |
| `distribution_docker_images` | `image_name, flavor` (flavor = OW build variant, NOT a Docker registry tag) |
| `distribution_packages` | `package_type ∈ {DEB, RPM}, package_name` |

API mapper recomposes the v1-v3 `GAV` CSV by reading Maven entries (sort_order), then file-URL entries (sort_order), concatenating canonically. `docker`, `DEB`, `RPM` are separate DSL fields; each family's order is preserved by its own `sort_order`.

#### Build-tool beans — `component_build_tool_beans`

DSL `build { tools { database { oracle { version = "11.2" } } ... } }` decomposes into typed rows. `sort_order` is per `component_configuration_id`.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `component_configuration_id` | UUID FK | References `component_configurations(id)` |
| `bean_type` | VARCHAR(30) NOT NULL | Discriminator: `oracleDatabase`, `cProduct`, `kProduct`, `dProduct`, `dDbProduct`, `odbc` |
| `tool_type` | VARCHAR(50), nullable | Optional sub-type hint (e.g., `PT_K`) |
| `settings_property` | TEXT, nullable | |
| `version_pattern` | TEXT, nullable | |
| `edition` | VARCHAR(50), nullable | `oracleDatabase` only; CHECK `edition IS NULL OR bean_type = 'oracleDatabase'` |
| `sort_order` | INT NOT NULL DEFAULT 0 | |

Resolver path: `EntityMappers.toBuildToolBean()` reconstructs the typed `BuildTool` subclass from a row; `toBuildParameters()` applies a `build.buildTools` marker override via `pickMarkerChildren`.

### 4.8 Cross-cutting

| Table | PK | Purpose |
|---|---|---|
| `audit_log` | `id BIGSERIAL` | Entity history; `old_value/new_value/change_diff` are TEXT (JSON via Hibernate) |
| `registry_config` | `key` | Global config blob (component-defaults, field-config); value TEXT (JSON) |
| `component_source` | `component_key` | Per-component flag git\|db for dual-read routing |
| `dependency_mappings` | `alias` | alias → component_key lookup |
| `git_history_import_state` | `import_key` | Async migration job state |

## 5. API mapping (v1-v3 backward compatibility)

All v1-v3 endpoints implemented as adapters over the new schema. Mapper rules:

| API field / shape | New schema source |
|---|---|
| `name` (v1-v3) | `components.component_key` (rename in mapper) |
| `componentKey` (v4) | `components.component_key` direct |
| `repositoryType` | `vcs_settings_entries.repository_type`; NULL → default GIT |
| `teamcityProjectUrl` | Computed at mapper: `<teamcity-base>/buildConfiguration/<project_id>` |
| `hotfixVersionFormat` (jira) | `components.jira_hotfix_version_format`; UI read-only |
| `escrow.buildTask` (legacy) | `component_configurations.build_tasks` (consolidated) |
| `doc: {component, majorVersion}` (singular) | `component_doc_links` filtered: for requested version X.Y.Z, pick row WHERE `major_version = 'X.Y'`; else fall back to row WHERE `major_version IS NULL`; else null |
| `docs[]` (v4 plural) | `component_doc_links` full list |
| `vcs.versionControlSystems` (v3 legacy) | Not produced — only emitted by dropped `GET /api/3/components` endpoint |
| `GAV` csv (v1-v3) | `distribution_maven_artifacts` ORDER BY sort_order, then `distribution_file_url_artifacts` ORDER BY sort_order; canonical concat |
| `docker` csv (v1-v3) | `distribution_docker_images` ORDER BY sort_order; format `image:flavor` when `flavor IS NOT NULL`, else `image` |
| Legacy variants-Map shape | Skip base row in enumeration when `is_synthetic_base = true` — fixes MIG-029 |

### 5.1 Endpoint kill-list

Effectively-dead endpoints (≤2 calls in 2 production days) are dropped or stubbed:

| Endpoint | Calls/2d | New behavior | HTTP code |
|---|---:|---|---:|
| `GET /api/3/components` | 0 | Remove handler | 410 Gone |
| `PUT /api/2/components-registry/service/updateCache` | 2 | Remove handler | 410 Gone (matches deployed) |
| `GET /api/2/components` (no params) | 2 | Return `[]` | 200 |
| `GET /api/2/projects/{k}/jira-component-version-ranges` | 1 | Return `[]` | 200 |
| `GET /api/1/components` (no filter) | 1 | Return `[]` | 200 |

All other v1-v3 endpoints preserve byte-for-byte response shape for unchanged components, validated against the prod-aligned fixture suite.

## 6. Migration approach

`POST /admin/migrate` is rewritten to populate the new schema from DSL. Async-job semantics (MIG-027/028) are unchanged.

### 6.1 Pre-pass discovery

1. Parse all DSL files via `EscrowConfigurationLoader.loadFullConfiguration` (unresolved form — preserves raw distribution strings with `${version}` placeholders).
2. Collect distinct `system` values → upsert `systems`.
3. Collect `Tools { name { ... } ... }` block contents → upsert `tools`.
4. Seed `labels` from installation's `validation-config.yaml`.

### 6.2 Per-component import (two-pass for `parentComponent` and aggregators)

**Pass 1** — create all components with `parent_component_id = NULL`:

```kotlin
val pendingParentByKey = mutableMapOf<String, String>()
for (dslComponent in allDslComponents) {
    val entity = mapToComponentEntity(dslComponent)
    entity.parentComponentId = null
    componentRepository.save(entity)
    dslComponent.parentComponentName?.let { parentKey ->
        pendingParentByKey[dslComponent.componentKey] = parentKey
    }
}
```

**Pass 2** — resolve `parentComponent` references and link aggregators:

```kotlin
for ((childKey, parentKey) in pendingParentByKey) {
    val parent = componentRepository.findByComponentKey(parentKey)
    if (parent == null) {
        log.warn("parentComponent='$parentKey' referenced by '$childKey' not found; leaving null")
        continue
    }
    val child = componentRepository.findByComponentKey(childKey)!!
    child.parentComponentId = parent.id
    componentRepository.save(child)
}
```

Missing parent references are tolerated (WARN log) — referenced component may be archived or in a different installation.

### 6.3 Aggregator handling

For each top-level DSL component:
1. If no nested `components { }` block → STANDALONE; create one `components` row with `component_group_id = NULL`.
2. Else (aggregator):
   - Classify REAL vs FAKE per the detection rule (§4.3).
   - Create `component_groups` row.
   - REAL: also create `components` row for the aggregator itself with `component_group_id` pointing to its own group.
   - For each sub-component (`EscrowConfigurationLoader` resolves parent defaults into the sub config): create `components` row with `component_group_id` pointing to the group.

### 6.4 Base row determination

For each component:
- If DSL has any top-level fields (vcsUrl, jira, build, escrow, distribution, etc.) → base row from those values, `is_synthetic_base = false`.
- If DSL has only version-range blocks (no top-level fields) → base row populated purely from `Defaults.groovy` resolved values, `is_synthetic_base = true`.

### 6.5 Override row generation

For each version-range block in DSL:
- For each scalar attribute whose value differs from the base row: emit scalar override row (`overridden_attribute = 'aspect.field'`, single column set).
- For each child-collection change (multi-VCS, distribution_*, requiredTools): emit marker row (`overridden_attribute = '<marker>'`, all typed scalars NULL) + child rows attached.

Override detection deduplicates: a row is emitted only when the resolved value differs from the base row's resolved value.

### 6.6 Distribution parsing

`distribution { GAV = "csv1,csv2", docker = "img1,img2:flavor", DEB = "p1,p2", RPM = "p" }`:
- Split `GAV` CSV; for each entry:
  - Starts with `file://`/`http(s)://` → `distribution_file_url_artifacts` row (parse query string for `artifactId`, `classifier`); per-family sort_order counter.
  - Else parse `group:artifact[:ext[:classifier]]` → `distribution_maven_artifacts` row; per-family sort_order counter.
- Split `docker` CSV; parse `image[:flavor]` (split on last `:`; flavor is the OW build variant, not a Docker registry tag); warn if flavor matches a version pattern.
- Split `DEB`, `RPM` CSV; one `distribution_packages` row per entry.

### 6.7 Tools and required tools

- Discover `Tools { ToolName { escrowEnvironmentVariable, targetLocation, sourceLocation, installScript } ... }` at top of any DSL file → upsert into `tools`.
- For each component's `build.requiredTools = "BuildEnv,Whiskey"` csv: split and link `component_required_tools` rows.
- Missing tool reference (referenced tool not in registry) → WARN log; relationship skipped.

### 6.8 `${version}` placeholders

DSL strings containing `${version}`, `${env.X}`, `${major}`, etc. are stored **verbatim** in the database. Placeholder expansion happens at runtime by the consumer (build tooling), not at migration time.

## 7. Validation rules

`ComponentManagementServiceImpl.create / update` enforces:

- Base row: at least one typed column may be filled; child rows allowed.
- Scalar override row: `overridden_attribute` is a known `aspect.field`; exactly one matching typed column non-NULL; no child rows.
- Marker override row: `overridden_attribute` ∈ marker set; all typed scalar columns NULL (DB CHECK also enforces this); child rows attached.
- Version_range overlap (transitional): override rows within one component must not partially overlap (equal ranges blocked by UNIQUE; strict containment and disjoint allowed).

DB-level CHECK constraints defend against migration / bulk-load bypassing the service layer (one CHECK per marker name).

## 8. Test strategy

### 8.1 Layer 1: synthetic DSL fixtures (committed to repo)

Handcrafted fixtures in `src/test/resources/dsl-fixtures/` cover each pattern with anonymized names:

```
real-aggregator-multi-vcs.groovy        — multi-VCS named entries, real URL
real-aggregator-single-vcs.groovy       — single-VCS form 1 or form 3
fake-aggregator-no-vcs.groovy           — no vcsUrl at all
fake-aggregator-placeholder-url.groovy  — vcsUrl = ".../fake/fake.git"
fake-aggregator-stub-marker.groovy      — artifactId = "X-stub"
fake-aggregator-fake-marker.groovy      — artifactId = "fake"
standalone-component.groovy             — no nested components block
version-range-only.groovy               — no top-level DSL fields; tests is_synthetic_base
multi-aspect-divergence.groovy          — build, vcs, jira, escrow each change at different ranges
all-distribution-families.groovy        — Maven + fileUrl + Docker + DEB/RPM together
```

Unit tests assert per-pattern classification using synthetic names only.

### 8.2 Layer 2: integration against real DSL (env-gated)

```kotlin
@EnabledIfEnvironmentVariable(named = "CRS_DSL_PATH", matches = ".+")
@Test
fun `migration on full DSL produces expected aggregator structure`() {
    runMigration(System.getenv("CRS_DSL_PATH"))
    // property-based, not name-specific:
    assertThat(componentGroupRepository.findAll()).isNotEmpty
    // ...
}
```

Disabled in public CI; runs in internal CI with private-DSL access.

### 8.3 Layer 3: prod-aligned baselines (internal CI only)

Baseline JSON files in internal artifact storage record exact counts per installation. Internal CI asserts Layer-2 outputs against these baselines.

## 9. Anonymization

This document and all artifacts published to the public repository use anonymized names and structural descriptions. Real component names from production DSL appear only in internal working notes (kept outside the repository).
