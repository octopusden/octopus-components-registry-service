-- =============================================================================
-- CRS schema v2 — consolidated baseline.
--
-- Replaces V1..V6 (component_versions, polymorphic FKs, JSONB metadata, text[]
-- arrays). Project not yet in production: QA/dev databases are wiped and
-- recreated against this baseline.
--
-- Design: ADR-014. Detailed reference: docs/db-migration/schema-spec.md.
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
    display_name                VARCHAR(255),
    product_type                VARCHAR(20),                            -- app-validated against ProductTypes enum
    client_code                 VARCHAR(255),
    archived                    BOOLEAN NOT NULL DEFAULT false,
    solution                    BOOLEAN,
    parent_component_id         UUID REFERENCES components(id),         -- DSL parentComponent = "X" reference
    component_group_id          UUID REFERENCES component_groups(id),   -- aggregator membership
    release_manager             VARCHAR(255),
    security_champion           VARCHAR(255),
    copyright                   TEXT,
    releases_in_default_branch  BOOLEAN,
    -- jira fields that never vary per-version:
    jira_display_name           VARCHAR(255),
    jira_hotfix_version_format  VARCHAR(255),                           -- UI read-only (inherited from Defaults)
    vcs_external_registry       TEXT,
    -- distribution fields that never vary per-version:
    distribution_explicit       BOOLEAN,
    distribution_external       BOOLEAN,
    -- audit / optimistic locking:
    version                     BIGINT NOT NULL DEFAULT 0,              -- @Version
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_components_archived          ON components(archived);
CREATE INDEX idx_components_product_type      ON components(product_type);
CREATE INDEX idx_components_parent            ON components(parent_component_id);
CREATE INDEX idx_components_group             ON components(component_group_id);
CREATE INDEX idx_components_live              ON components(component_key) WHERE archived = false;


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
    build_system_version                        VARCHAR(50),
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
    jira_major_version_format                   VARCHAR(255),
    jira_release_version_format                 VARCHAR(255),
    jira_build_version_format                   VARCHAR(255),
    jira_line_version_format                    VARCHAR(255),
    jira_version_prefix                         VARCHAR(255),
    jira_version_format                         VARCHAR(255),
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
    CHECK (
        (row_type IN ('BASE', 'RANGE_PRESENCE') AND overridden_attribute IS NULL)
        OR (row_type = 'MARKER'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute IN (
                'vcs.settings', 'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages', 'build.requiredTools',
                'build.buildTools'
            ))
        OR (row_type = 'SCALAR_OVERRIDE'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute NOT IN (
                'vcs.settings', 'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages', 'build.requiredTools',
                'build.buildTools'
            ))
    ),

    -- All 28 typed scalar columns must be NULL on MARKER and RANGE_PRESENCE
    -- rows (children carry the data on markers; presence rows are pure
    -- enumeration anchors). The full "scalar override = exactly one non-NULL
    -- typed column" rule is enforced in the service layer (too verbose for
    -- CHECK with 28 columns).
    CHECK (row_type NOT IN ('MARKER', 'RANGE_PRESENCE') OR (
        build_system IS NULL AND build_system_version IS NULL AND java_version IS NULL
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
        AND jira_major_version_format IS NULL AND jira_release_version_format IS NULL
        AND jira_build_version_format IS NULL AND jira_line_version_format IS NULL
        AND jira_version_prefix IS NULL AND jira_version_format IS NULL
    ))
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

CREATE TABLE component_systems (
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    system_code  VARCHAR(50) NOT NULL REFERENCES systems(code),
    PRIMARY KEY (component_id, system_code)
);
CREATE INDEX idx_component_systems_system ON component_systems(system_code);

CREATE TABLE component_required_tools (
    component_configuration_id UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    tool_name                  VARCHAR(100) NOT NULL REFERENCES tools(name),
    PRIMARY KEY (component_configuration_id, tool_name)
);
CREATE INDEX idx_required_tools_tool ON component_required_tools(tool_name);


-- -----------------------------------------------------------------------------
-- 1:N children of components (never per-version)
-- -----------------------------------------------------------------------------
CREATE TABLE component_artifact_ids (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id      UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    group_pattern     TEXT NOT NULL,
    artifact_pattern  TEXT NOT NULL
);
CREATE INDEX idx_artifact_ids_component        ON component_artifact_ids(component_id);
CREATE INDEX idx_artifact_ids_group_artifact   ON component_artifact_ids(group_pattern, artifact_pattern);

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
