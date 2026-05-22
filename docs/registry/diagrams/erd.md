# Database schema — ERD (schema v2 / Model A')

Entity-relationship diagram of the v2 schema baseline (`V1__schema.sql`). The full column-by-column specification lives in [schema-spec.md](../schema-spec.md); the design rationale is in [ADR-014](../adr/014-schema-v2.md).

23 tables across 7 groups. The diagram is split into views to stay readable.

## Core + per-version configurations

The `components` table holds one row per component (canonical identity). All per-version data lives on `component_configurations` — base row + sparse single-attribute overrides + marker rows for child-collection replacement.

```mermaid
erDiagram
    components ||--o{ component_configurations : "1:N"
    components ||--o{ component_artifact_ids : "1:N"
    components ||--o{ distribution_security_groups : "1:N"
    components ||--o{ component_teamcity_projects : "1:N"
    components ||--o{ component_doc_links : "1:N"
    components ||--o{ component_source : "1:1"

    components }o--o| components : "parent_component_id"
    components }o--o| component_groups : "component_group_id"

    components {
        uuid id PK
        string component_name UK
        string display_name
        string product_type
        string component_owner
        uuid parent_component_id FK
        uuid component_group_id FK
        boolean archived
        timestamp updated_at
        bigint version "@Version"
    }

    component_configurations {
        uuid id PK
        uuid component_id FK
        string version_range
        string row_type "BASE | SCALAR_OVERRIDE | MARKER_OVERRIDE | RANGE_PRESENCE"
        string overridden_attribute
        boolean is_synthetic_base
        string build_system
        string jira_project_key
        string vcs_url
        string escrow_build_task
        string repository_url
        string repository_path
    }

    component_groups {
        uuid id PK
        string group_key UK
        boolean is_fake
        string aggregator_name
    }

    component_source {
        uuid component_id PK,FK
        string source "git | db"
        timestamp migrated_at
        string migrated_by
    }
```

## Dictionaries + M:N junctions

Reference data is admin-managed (or auto-discovered during migration). Components reference dictionaries through pure M:N junction tables.

```mermaid
erDiagram
    labels ||--o{ component_labels : "1:N"
    systems ||--o{ component_systems : "1:N"
    tools ||--o{ component_required_tools : "1:N"

    components ||--o{ component_labels : "1:N"
    components ||--o{ component_systems : "1:N"
    components ||--o{ component_required_tools : "1:N"

    labels {
        uuid id PK
        string label_code UK
        string display_name
    }
    systems {
        uuid id PK
        string system_code UK
    }
    tools {
        uuid id PK
        string tool_code UK
    }

    component_labels {
        uuid component_id PK,FK
        uuid label_id PK,FK
    }
    component_systems {
        uuid component_id PK,FK
        uuid system_id PK,FK
    }
    component_required_tools {
        uuid component_id PK,FK
        uuid tool_id PK,FK
        string min_version
    }
```

## Configuration children + distribution family split

Per-configuration relationships fan out to typed family tables. The distribution split is the key reason ADR-014 chose Model A' over Model A (single wide row): we keep type safety while modelling four distinct distribution families.

```mermaid
erDiagram
    component_configurations ||--o{ vcs_settings_entries : "1:N"
    component_configurations ||--o{ component_build_tool_beans : "1:N"
    component_configurations ||--o{ distribution_maven_artifacts : "1:N"
    component_configurations ||--o{ distribution_file_url_artifacts : "1:N"
    component_configurations ||--o{ distribution_docker_images : "1:N"
    component_configurations ||--o{ distribution_packages : "1:N"

    vcs_settings_entries {
        uuid id PK
        uuid configuration_id FK
        string name "NULL for single-VCS"
        string vcs_url
        string vcs_type
        int sort_order
    }
    component_build_tool_beans {
        uuid id PK
        uuid configuration_id FK
        string bean_kind "kts-script | provider | ..."
        string payload
    }
    distribution_maven_artifacts {
        uuid id PK
        uuid configuration_id FK
        string group_id
        string artifact_pattern
        int sort_order
    }
    distribution_file_url_artifacts {
        uuid id PK
        uuid configuration_id FK
        string url_pattern
        int sort_order
    }
    distribution_docker_images {
        uuid id PK
        uuid configuration_id FK
        string image_pattern
        int sort_order
    }
    distribution_packages {
        uuid id PK
        uuid configuration_id FK
        string package_type "DEB | RPM"
        string package_name
        int sort_order
    }
```

## Cross-cutting infrastructure tables

Audit and operational state — not part of the component model proper.

| Table | Purpose |
|---|---|
| `audit_log` | Append-only audit trail for v4 CRUD operations. JSON before/after via Hibernate `@JdbcTypeCode(SqlTypes.JSON)`. Source for `/audit/**` endpoints. Decision: [ADR-005](../adr/005-audit-log.md). |
| `registry_config` | Singleton-shaped key/value table for runtime configuration (e.g. field-visibility overrides). |
| `dependency_mappings` | Component → component dependency mapping registry (SYS-037). |
| `git_history_import_state` | Per-component cursor for the `/migrate-history` backfill job (MIG-026). |

## Resolve algorithm in one line

For `(componentName, version)`: load `BASE` row + all `SCALAR_OVERRIDE` and `MARKER_OVERRIDE` rows whose `version_range` contains `version`; merge per attribute, with `OVERRIDE` rows winning over `BASE`. Synthetic-base rows (`is_synthetic_base = true`) are skipped on legacy enumeration endpoints to eliminate the MIG-029 spurious-row issue at source. Full algorithm + edge cases: [schema-spec.md §"Resolve algorithm"](../schema-spec.md).
