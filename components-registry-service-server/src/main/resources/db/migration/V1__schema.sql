-- =============================================================================
-- CRS schema v2 — consolidated baseline.
--
-- Replaces V1..V6 (component_versions, polymorphic FKs, JSONB metadata, text[]
-- arrays). Project not yet in production: QA/dev databases are wiped and
-- recreated against this baseline.
--
-- Design: ADR-014. Detailed reference: docs/registry/schema-spec.md.
--
-- Key model elements:
--   - Model A': wide typed `component_configurations` with four row shapes
--     (base, scalar override, marker override, range presence). `row_type` is
--     the source-of-truth classifier; `overridden_attribute` is the payload
--     discriminator for scalar/marker rows only. UNIQUE(component_id, version_range,
--     overridden_attribute). Partial unique index ensures one base per component.
--   - Unified VCS: SINGLE = MULTI with one entry. `name` is always non-null.
--     All VCS scalars live on `vcs_settings_entries` regardless of cardinality.
--   - Reference dictionaries: labels, systems, tools (admin-managed).
--   - Aggregator groups: `component_groups` + `components.component_group_id`.
--   - Distribution split into 4 specialized child tables (Maven/file-URL/Docker/packages).
--   - is_synthetic_base flag fixes MIG-029 (legacy variants enumeration skips
--     synthetic base; hot-path resolve always uses base as fallback).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()


-- -----------------------------------------------------------------------------
-- component_groups: aggregator grouping (created BEFORE components — FK target).
-- -----------------------------------------------------------------------------
CREATE TABLE component_groups (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_key  VARCHAR(255) NOT NULL UNIQUE,
    is_fake    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_component_groups_key ON component_groups(group_key);


-- -----------------------------------------------------------------------------
-- components: identity + per-component fields that never vary per-version.
-- -----------------------------------------------------------------------------
CREATE TABLE components (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_key               VARCHAR(255) NOT NULL UNIQUE,
    component_owner             VARCHAR(255),
    display_name                VARCHAR(255) UNIQUE,                    -- nullable (NULL = no componentDisplayName, preserves legacy $.name) + unique on non-null values
    product_type                VARCHAR(20),                            -- app-validated against ProductTypes enum
    client_code                 VARCHAR(255),
    archived                    BOOLEAN NOT NULL DEFAULT false,
    solution                    BOOLEAN,
    parent_component_id         UUID REFERENCES components(id),         -- DSL parentComponent = "X" reference
    component_group_id          UUID REFERENCES component_groups(id),   -- aggregator membership
    can_be_parent               BOOLEAN NOT NULL DEFAULT false,         -- true if referenced as a parent (aggregator); seeded by import, editable via v4
    -- release_manager / security_champion are NO LONGER scalar columns here.
    -- They became ordered multi-value child tables (component_release_managers,
    -- component_security_champions) further down — see "1:N children of
    -- components". component_owner stays single-value scalar.
    copyright                   TEXT,
    releases_in_default_branch  BOOLEAN,
    -- jira fields that never vary per-version:
    jira_display_name           VARCHAR(255),
    -- Per-component base value for jira.componentVersionFormat.hotfixVersionFormat;
    -- inherited from Defaults at import time. Per-range overrides live on
    -- component_configurations.jira_hotfix_version_format and take precedence
    -- when present (see EntityMappers.buildJiraComponent).
    jira_hotfix_version_format  VARCHAR(255),
    vcs_external_registry       TEXT,
    -- distribution fields that never vary per-version:
    distribution_explicit       BOOLEAN,
    distribution_external       BOOLEAN,
    -- system assignment: a component belongs to at most one system. Collapsed
    -- from the M:N junction `component_systems` in this iteration (per
    -- `project_db_fresh_on_deploy.md`, no backfill is needed; every CRS
    -- environment recreates the DB on deploy). Nullable on the column —
    -- business says "should be set" but the strict-contract requirement on
    -- `system` was not part of the original ui-swift-sloth plan, so we do
    -- not force NOT NULL here. If/when that requirement lands, tighten via
    -- a service-layer validator and (optionally) a follow-up migration.
    --
    -- The FK to `systems(code)` is added via `ALTER TABLE` after the
    -- `systems` table is declared further down in this file — Postgres
    -- requires the referenced table to exist before the CREATE TABLE that
    -- references it (even inside the same transaction).
    system_code                 VARCHAR(50),
    -- audit / optimistic locking:
    version                     BIGINT NOT NULL DEFAULT 0,              -- @Version
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_components_archived          ON components(archived);
CREATE INDEX idx_components_product_type      ON components(product_type);
CREATE INDEX idx_components_parent            ON components(parent_component_id);
-- Partial index for the `?canBeParent=true` parent-picker filter — only the
-- aggregator minority is indexed (the false majority never matches the filter).
CREATE INDEX idx_components_can_be_parent     ON components(can_be_parent) WHERE can_be_parent = true;
CREATE INDEX idx_components_group             ON components(component_group_id);
CREATE INDEX idx_components_live              ON components(component_key) WHERE archived = false;
-- Index supports the `?system=A,B` list-filter (`IN (...)`) on the scalar
-- system_code column. Partial: `IS NOT NULL` skips the long tail of
-- unassigned components from the index entirely (those never match a
-- non-empty filter).
CREATE INDEX idx_components_system_code       ON components(system_code) WHERE system_code IS NOT NULL;


-- -----------------------------------------------------------------------------
-- component_configurations: per-(component, version_range) sparse rows.
--
-- Four row shapes (enforced by `row_type` CHECK + targeted DB CHECKs):
--   1) BASE            — overridden_attribute IS NULL; all typed columns may be filled
--   2) SCALAR_OVERRIDE — overridden_attribute = 'aspect.field' (non-marker);
--                        exactly one typed col non-NULL (enforced in service layer)
--   3) MARKER          — overridden_attribute in marker set; ALL typed cols NULL;
--                        child rows attached via FK
--   4) RANGE_PRESENCE  — overridden_attribute IS NULL; ALL typed cols NULL.
--                        Storage artifact: marks that a DSL `componentVersion(R)`
--                        block exists for the given range so resolver enumerates
--                        it even when scalars/markers match base. Hidden from
--                        V4 editor APIs; resolver-only.
--
-- `row_type` is the source of truth. `overridden_attribute` is the payload
-- discriminator for SCALAR_OVERRIDE/MARKER and MUST be NULL for BASE/RANGE_PRESENCE.
-- -----------------------------------------------------------------------------
CREATE TABLE component_configurations (
    id                                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id                                UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    version_range                               VARCHAR(255) NOT NULL,
    overridden_attribute                        VARCHAR(50),                  -- NULL for BASE/RANGE_PRESENCE; non-NULL for SCALAR_OVERRIDE/MARKER
    row_type                                    VARCHAR(32) NOT NULL,         -- BASE | SCALAR_OVERRIDE | MARKER | RANGE_PRESENCE
    is_synthetic_base                           BOOLEAN NOT NULL DEFAULT false, -- true on base row when DSL had no top-level fields
    -- BUILD aspect:
    build_system                                VARCHAR(50),
    java_version                                VARCHAR(50),
    maven_version                               VARCHAR(50),
    gradle_version                              VARCHAR(50),
    build_file_path                             TEXT,
    deprecated                                  BOOLEAN,
    required_project                            BOOLEAN,
    project_version                             VARCHAR(255),
    system_properties                           TEXT,
    build_tasks                                 TEXT,                          -- used for build only (escrow has its own column)
    escrow_build_task                           TEXT,                          -- escrow.buildTask (was V2 incremental, folded into V1)
    -- ESCROW aspect:
    escrow_provided_dependencies                TEXT,                          -- comma-separated
    escrow_reusable                             BOOLEAN,
    escrow_generation                           VARCHAR(50),
    escrow_disk_space                           VARCHAR(50),
    escrow_additional_sources                   TEXT,                          -- comma-separated
    escrow_gradle_include_configurations        TEXT,
    escrow_gradle_exclude_configurations        TEXT,
    escrow_gradle_include_test_configurations   BOOLEAN,
    -- JIRA aspect (per-version overridable fields):
    jira_project_key                            VARCHAR(50),
    jira_technical                              BOOLEAN,
    jira_minor_version_format                   VARCHAR(255),
    jira_release_version_format                 VARCHAR(255),
    jira_build_version_format                   VARCHAR(255),
    jira_line_version_format                    VARCHAR(255),
    jira_version_prefix                         VARCHAR(255),
    jira_version_format                         VARCHAR(255),
    -- Per-range override for jira.componentVersionFormat.hotfixVersionFormat.
    -- Defaults / per-component base lives on components.jira_hotfix_version_format.
    -- The resolver layers this on top of the base when present.
    jira_hotfix_version_format                  VARCHAR(255),
    created_at                                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    UNIQUE (component_id, version_range, overridden_attribute),

    -- row_type domain.
    CHECK (row_type IN ('BASE', 'SCALAR_OVERRIDE', 'MARKER', 'RANGE_PRESENCE')),

    -- Positive taxonomy CHECK pairing row_type with overridden_attribute.
    -- SQL three-valued logic: CHECK passes when the predicate is TRUE *or*
    -- UNKNOWN. Each branch is written so its conjunction evaluates to a
    -- known TRUE/FALSE for every row:
    --   - The MARKER branch guards the IN-list with `overridden_attribute IS NOT NULL`
    --     because `NULL IN (...)` returns UNKNOWN, which would otherwise let
    --     `row_type='MARKER', overridden_attribute=NULL` slip through.
    --   - The SCALAR_OVERRIDE branch likewise pins non-NULL before evaluating
    --     `NOT IN (...)`, and forbids reusing a marker name as a scalar
    --     attribute path.
    -- MIG-047 added `group-artifact-pattern` as a synthetic import-internal MARKER
    -- name (NOT in MarkerAttributes.ALL — see EntityMappers.kt:65 kdoc). The
    -- allowlist below is the union of MarkerAttributes.ALL and that synthetic
    -- name; the SCALAR_OVERRIDE NOT-IN list is its symmetric exclusion.
    CHECK (
        (row_type IN ('BASE', 'RANGE_PRESENCE') AND overridden_attribute IS NULL)
        OR (row_type = 'MARKER'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute IN (
                'vcs.settings', 'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages', 'build.requiredTools',
                'build.buildTools', 'group-artifact-pattern'
            ))
        OR (row_type = 'SCALAR_OVERRIDE'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute NOT IN (
                'vcs.settings', 'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages', 'build.requiredTools',
                'build.buildTools', 'group-artifact-pattern'
            ))
    ),

    -- All 28 typed scalar columns must be NULL on MARKER and RANGE_PRESENCE
    -- rows (children carry the data on markers; presence rows are pure
    -- enumeration anchors). The full "scalar override = exactly one non-NULL
    -- typed column" rule is enforced in the service layer (too verbose for
    -- CHECK with 28 columns).
    CHECK (row_type NOT IN ('MARKER', 'RANGE_PRESENCE') OR (
        build_system IS NULL AND java_version IS NULL
        AND maven_version IS NULL AND gradle_version IS NULL AND build_file_path IS NULL
        AND deprecated IS NULL AND required_project IS NULL AND project_version IS NULL
        AND system_properties IS NULL AND build_tasks IS NULL AND escrow_build_task IS NULL
        AND escrow_provided_dependencies IS NULL AND escrow_reusable IS NULL
        AND escrow_generation IS NULL AND escrow_disk_space IS NULL
        AND escrow_additional_sources IS NULL
        AND escrow_gradle_include_configurations IS NULL
        AND escrow_gradle_exclude_configurations IS NULL
        AND escrow_gradle_include_test_configurations IS NULL
        AND jira_project_key IS NULL AND jira_technical IS NULL
        AND jira_minor_version_format IS NULL AND jira_release_version_format IS NULL
        AND jira_build_version_format IS NULL AND jira_line_version_format IS NULL
        AND jira_version_prefix IS NULL AND jira_version_format IS NULL
        AND jira_hotfix_version_format IS NULL
    )),

    -- UI-swift-sloth: BASE rows must declare a build_system. Column-level
    -- NOT NULL would clash with the consolidated CHECK above (which forces
    -- ALL 28 typed scalars to be NULL on MARKER / RANGE_PRESENCE rows); a
    -- targeted "BASE → build_system IS NOT NULL" defends the same invariant
    -- at the right layer. The service layer (`ComponentManagementServiceImpl
    -- .createComponent`) is the user-visible 400 path; this CHECK is
    -- defence-in-depth against direct DB writes, future bulk-loaders, or
    -- service-layer regressions that bypass controller validation.
    -- MARKER / RANGE_PRESENCE / SCALAR_OVERRIDE shapes are unchanged.
    --
    -- Per project_db_fresh_on_deploy.md: every CRS environment recreates
    -- the DB from scratch on deploy (pre-prod), so editing V1 here does
    -- not break Flyway checksum validation anywhere. Re-evaluate this
    -- assumption once CRS reaches a long-lived environment.
    CONSTRAINT chk_component_configurations_base_build_system
        CHECK (row_type <> 'BASE' OR build_system IS NOT NULL)
);

-- Partial unique index: at most one base row per component.
CREATE UNIQUE INDEX uq_component_configurations_one_base
    ON component_configurations(component_id)
    WHERE row_type = 'BASE';

-- Partial unique index: at most one RANGE_PRESENCE row per (component, range).
-- The broad UNIQUE(component_id, version_range, overridden_attribute) above
-- does NOT enforce this because Postgres treats NULLs as distinct in unique
-- constraints (NULLS DISTINCT is the default), and presence rows have NULL
-- overridden_attribute.
CREATE UNIQUE INDEX uq_component_configurations_one_range_presence
    ON component_configurations(component_id, version_range)
    WHERE row_type = 'RANGE_PRESENCE';

CREATE INDEX idx_config_component             ON component_configurations(component_id);
CREATE INDEX idx_config_component_range       ON component_configurations(component_id, version_range);
CREATE INDEX idx_config_jira_project_key      ON component_configurations(jira_project_key) WHERE jira_project_key IS NOT NULL;
CREATE INDEX idx_config_updated_at            ON component_configurations(updated_at);


-- -----------------------------------------------------------------------------
-- Reference dictionaries (admin-managed).
-- -----------------------------------------------------------------------------
CREATE TABLE labels (
    code        VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(255),
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE systems (
    code        VARCHAR(50) PRIMARY KEY,
    name        VARCHAR(255),
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Deferred FK from `components.system_code` to `systems(code)` — declared
-- here (after `systems` exists) because Postgres requires the referenced
-- table to exist before the FK constraint is created, even within a
-- single transaction. The column itself is defined in the CREATE TABLE
-- for `components` above.
--
-- ON DELETE SET NULL: removing a master system row should not be blocked
-- by lingering component references. Components keep their identity, lose
-- their system assignment, and become available again to the assignment
-- workflow. Choosing SET NULL over RESTRICT (the Postgres default) trades
-- a soft data-quality concern (orphaned components surfacing as
-- `system: null` on the API) for admin operability — deleting a
-- decommissioned system code from the dictionary is now a single
-- operation rather than a multi-step "find and rewrite every dependent
-- component first". The old M:N junction `component_systems` had the
-- same default-RESTRICT FK to `systems(code)`, but junction rows could
-- be removed independently of components; this clause preserves the
-- spirit of that decoupling.
ALTER TABLE components
    ADD CONSTRAINT fk_components_system
    FOREIGN KEY (system_code) REFERENCES systems(code) ON DELETE SET NULL;

CREATE TABLE tools (
    name                  VARCHAR(100) PRIMARY KEY,
    escrow_env_variable   VARCHAR(255),
    target_location       TEXT,
    source_location       TEXT,
    install_script        TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- M:N junctions
-- -----------------------------------------------------------------------------
CREATE TABLE component_labels (
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    label_code   VARCHAR(100) NOT NULL REFERENCES labels(code),
    PRIMARY KEY (component_id, label_code)
);
CREATE INDEX idx_component_labels_label ON component_labels(label_code);

-- (M:N junction `component_systems` removed in this iteration. A
-- component now carries its system assignment as the scalar
-- `components.system_code` column above. The master `systems` table
-- below is unchanged — still used by `/meta/systems/dictionary`.)

CREATE TABLE component_required_tools (
    component_configuration_id UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    tool_name                  VARCHAR(100) NOT NULL REFERENCES tools(name),
    PRIMARY KEY (component_configuration_id, tool_name)
);
CREATE INDEX idx_required_tools_tool ON component_required_tools(tool_name);


-- -----------------------------------------------------------------------------
-- 1:N children of components (never per-version)
-- -----------------------------------------------------------------------------
-- Artifact-ownership mappings (replaces the old `component_artifact_ids` table and
-- the per-range `group-artifact-pattern` marker as ownership carrier). A component
-- owns a LIST of mappings; each mapping declares a group-list + an ownership MODE
-- (EXPLICIT | ALL_EXCEPT_CLAIMED | ALL) for a version range. `version_range` =
-- ALL_VERSIONS for the base mapping, or an existing component-configuration range
-- for a per-range override (override REPLACES base for that range). `sort_order`
-- preserves declaration order — sort_order=0 is the PRIMARY mapping rendered into
-- the legacy v1-v3 single (groupIdPattern, artifactIdPattern) pair. Surrogate UUID
-- PK with NO composite (component_id,..,sort_order) UNIQUE — the service's
-- clear/re-add edit pattern INSERTs new rows before deleting orphans, which a
-- composite unique on sort_order would collide with (same rationale as
-- component_release_managers); sort_order determinism + the "≤1 mapping per group
-- token per (component,range)" invariant are enforced in the service layer.
CREATE TABLE component_artifact_mappings (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id      UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    version_range     TEXT NOT NULL,
    group_pattern     TEXT NOT NULL,
    artifact_id_mode  VARCHAR(20) NOT NULL,
    sort_order        INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_artifact_mappings_component   ON component_artifact_mappings(component_id);
CREATE INDEX idx_artifact_mappings_group       ON component_artifact_mappings(group_pattern);

-- Literal artifact tokens of an EXPLICIT mapping (one token per row, order preserved).
-- ALL / ALL_EXCEPT_CLAIMED mappings have NO token rows — the catch-all is derived
-- from the mode at render/resolve time. Tokens are stored UNESCAPED (literal); the
-- legacy-wire / DSL render escapes regex metacharacters.
CREATE TABLE component_artifact_mapping_tokens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mapping_id        UUID NOT NULL REFERENCES component_artifact_mappings(id) ON DELETE CASCADE,
    artifact_pattern  TEXT NOT NULL,
    sort_order        INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_artifact_mapping_tokens_mapping ON component_artifact_mapping_tokens(mapping_id);

-- Ordered multi-value people: release managers and security champions. Mirror
-- the surrogate-UUID-PK shape (NOT a composite (component_id, sort_order) PK). The
-- clear/re-add edit pattern used by the service can INSERT new rows before deleting
-- orphans; a composite PK on sort_order would collide, so the surrogate UUID key is
-- used instead. `sort_order` preserves the ordered list (first = primary). Username
-- uniqueness within a component is enforced by the service-layer keep-first
-- dedupe (ComponentEntity.replace*Usernames), not a DB UNIQUE constraint.
-- Each table has a `component_id` FK index — Postgres does not auto-index FKs,
-- and lazy-collection loads + cascade deletes need it.
CREATE TABLE component_release_managers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    username     VARCHAR(255) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_component_release_managers_component
    ON component_release_managers(component_id);

CREATE TABLE component_security_champions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    username     VARCHAR(255) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_component_security_champions_component
    ON component_security_champions(component_id);

CREATE TABLE distribution_security_groups (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    group_type   VARCHAR(20) NOT NULL DEFAULT 'read',                -- read | write
    group_name   VARCHAR(255) NOT NULL
);
CREATE INDEX idx_dist_security_component ON distribution_security_groups(component_id);

CREATE TABLE component_teamcity_projects (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    project_id   VARCHAR(255) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_component_teamcity     ON component_teamcity_projects(component_id);
CREATE INDEX idx_teamcity_project_id    ON component_teamcity_projects(project_id);

CREATE TABLE component_doc_links (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id        UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    doc_component_key   VARCHAR(255) NOT NULL,                       -- soft reference; target may be archived or out-of-installation
    major_version       VARCHAR(50),                                 -- nullable; null = applies to any major
    sort_order          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (component_id, doc_component_key, major_version)
);
CREATE INDEX idx_doc_links_component ON component_doc_links(component_id);
CREATE INDEX idx_doc_links_doc_key   ON component_doc_links(doc_component_key);
-- PG treats NULLs as distinct in UNIQUE by default, so the table-level UNIQUE
-- above does not enforce at-most-one fallback row (major_version IS NULL) per
-- (component, doc_component_key). Add a partial index to enforce that.
CREATE UNIQUE INDEX uq_doc_links_null_major_version
    ON component_doc_links (component_id, doc_component_key)
    WHERE major_version IS NULL;


-- -----------------------------------------------------------------------------
-- 1:N children of component_configurations (per-version-rangeable)
-- -----------------------------------------------------------------------------
-- Unified VCS model: all VCS entries have a non-null name.
CREATE TABLE vcs_settings_entries (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    name                        VARCHAR(255) NOT NULL,                       -- DSL-assigned root name: 'main' for inline form, named-block key otherwise.
    vcs_path                    TEXT NOT NULL,
    branch                      TEXT,
    tag                         TEXT,
    hotfix_branch               TEXT,
    repository_type             VARCHAR(20),                                 -- GIT/MERCURIAL/CVS (Java enum); typically GIT
    sort_order                  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_vcs_entries_config ON vcs_settings_entries(component_configuration_id);

-- Distribution split: 4 specialized child tables, per-family sort_order preserved.
CREATE TABLE distribution_maven_artifacts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    group_pattern               TEXT NOT NULL,
    artifact_pattern            TEXT NOT NULL,
    extension                   VARCHAR(50),
    classifier                  TEXT,
    sort_order                  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dist_maven_config ON distribution_maven_artifacts(component_configuration_id);
CREATE INDEX idx_dist_maven_group  ON distribution_maven_artifacts(group_pattern);

CREATE TABLE distribution_file_url_artifacts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    url                         TEXT NOT NULL,
    artifact_id                 TEXT,                                        -- from ?artifactId= query param
    classifier                  TEXT,                                        -- from ?classifier= query param
    sort_order                  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dist_file_url_config ON distribution_file_url_artifacts(component_configuration_id);

CREATE TABLE distribution_docker_images (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    image_name                  TEXT NOT NULL,
    flavor                      VARCHAR(255),                                -- OW build variant (e.g. "amazon"), NOT a Docker registry tag
    sort_order                  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dist_docker_config ON distribution_docker_images(component_configuration_id);

CREATE TABLE distribution_packages (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    package_type                VARCHAR(10) NOT NULL,                        -- DEB | RPM
    package_name                VARCHAR(255) NOT NULL,
    sort_order                  INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_dist_packages_config ON distribution_packages(component_configuration_id);

-- Build-tool beans: structured build-tool requirements that cannot be represented
-- as plain tool-name strings in `component_required_tools`.
-- Supports 6 bean types: oracleDatabase, cProduct, kProduct, dProduct, dDbProduct, odbc.
-- `edition` is only valid for oracleDatabase (enforced by cross-column CHECK).
CREATE TABLE component_build_tool_beans (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    bean_type                   VARCHAR(32) NOT NULL,
    tool_type                   VARCHAR(32),
    settings_property           VARCHAR(64),
    version_pattern             TEXT,
    edition                     VARCHAR(32),
    sort_order                  INT NOT NULL DEFAULT 0,
    CHECK (bean_type IN ('oracleDatabase','cProduct','kProduct','dProduct','dDbProduct','odbc')),
    CHECK (edition IS NULL OR bean_type = 'oracleDatabase')
);
CREATE INDEX idx_build_tool_beans_config ON component_build_tool_beans(component_configuration_id);


-- -----------------------------------------------------------------------------
-- Cross-cutting (independent of components / configurations)
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       VARCHAR(255) NOT NULL,
    action          VARCHAR(20) NOT NULL,                                    -- CREATE | UPDATE | DELETE
    changed_by      VARCHAR(255),
    changed_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    old_value       TEXT,                                                    -- JSON (@JdbcTypeCode(SqlTypes.JSON))
    new_value       TEXT,
    change_diff     TEXT,
    correlation_id  VARCHAR(255),
    source          VARCHAR(20) NOT NULL DEFAULT 'api'
);
CREATE INDEX idx_audit_entity        ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_changed_at    ON audit_log(changed_at);
CREATE INDEX idx_audit_changed_by    ON audit_log(changed_by);
CREATE INDEX idx_audit_correlation   ON audit_log(correlation_id);
CREATE INDEX idx_audit_source        ON audit_log(source);

CREATE TABLE registry_config (
    key         VARCHAR(255) PRIMARY KEY,
    value       TEXT NOT NULL DEFAULT '{}',                                  -- JSON config blob
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE component_source (
    component_key VARCHAR(255) PRIMARY KEY,
    source        VARCHAR(10) NOT NULL DEFAULT 'git',                        -- git | db
    migrated_at   TIMESTAMP WITH TIME ZONE,
    migrated_by   VARCHAR(255)
);
CREATE INDEX idx_component_source ON component_source(source);

CREATE TABLE dependency_mappings (
    alias          VARCHAR(255) PRIMARY KEY,
    component_key  VARCHAR(255) NOT NULL
);

CREATE TABLE git_history_import_state (
    import_key   VARCHAR(64) PRIMARY KEY,
    target_ref   VARCHAR(255) NOT NULL,
    target_sha   VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
