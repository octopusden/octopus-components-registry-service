-- Component source routing table
CREATE TABLE component_source (
    component_name VARCHAR(255) PRIMARY KEY,
    source VARCHAR(10) NOT NULL DEFAULT 'git',
    migrated_at TIMESTAMP WITH TIME ZONE,
    migrated_by VARCHAR(255)
);

-- Main components table
CREATE TABLE components (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    component_owner VARCHAR(255),
    display_name VARCHAR(255),
    product_type VARCHAR(50),
    system text[] NOT NULL DEFAULT '{}',
    client_code VARCHAR(255),
    archived BOOLEAN NOT NULL DEFAULT false,
    solution BOOLEAN,
    parent_component_id UUID REFERENCES components(id),
    metadata JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Component versions with version range
CREATE TABLE component_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    version_range VARCHAR(255) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Build configurations (polymorphic owner: component OR component_version)
CREATE TABLE build_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID REFERENCES components(id) ON DELETE CASCADE,
    component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE,
    build_system VARCHAR(50),
    java_version VARCHAR(20),
    build_file_path VARCHAR(500),
    deprecated BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT chk_build_config_owner CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    )
);

-- Escrow configurations (polymorphic owner)
CREATE TABLE escrow_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID REFERENCES components(id) ON DELETE CASCADE,
    component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE,
    build_task VARCHAR(500),
    provided_dependencies TEXT,
    reusable BOOLEAN,
    generation VARCHAR(50),
    disk_space VARCHAR(50),
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT chk_escrow_config_owner CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    )
);

-- VCS settings (polymorphic owner)
CREATE TABLE vcs_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID REFERENCES components(id) ON DELETE CASCADE,
    component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE,
    vcs_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE',
    external_registry VARCHAR(500),
    CONSTRAINT chk_vcs_settings_owner CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    )
);

-- VCS settings entries (for MULTIPLY type, multiple roots)
CREATE TABLE vcs_settings_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vcs_settings_id UUID NOT NULL REFERENCES vcs_settings(id) ON DELETE CASCADE,
    name VARCHAR(255),
    vcs_path VARCHAR(1000) NOT NULL,
    repository_type VARCHAR(20) NOT NULL DEFAULT 'GIT',
    tag VARCHAR(500),
    branch VARCHAR(500),
    hotfix_branch VARCHAR(500)
);

-- Distributions (polymorphic owner)
CREATE TABLE distributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID REFERENCES components(id) ON DELETE CASCADE,
    component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE,
    explicit BOOLEAN NOT NULL DEFAULT false,
    external BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT chk_distribution_owner CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    )
);

-- Distribution artifacts (GAV, DEB, RPM, Docker)
CREATE TABLE distribution_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    distribution_id UUID NOT NULL REFERENCES distributions(id) ON DELETE CASCADE,
    artifact_type VARCHAR(20) NOT NULL,
    group_pattern VARCHAR(500),
    artifact_pattern VARCHAR(500),
    classifier VARCHAR(255),
    extension VARCHAR(50),
    name VARCHAR(500),
    tag VARCHAR(500)
);

-- Distribution security groups
CREATE TABLE distribution_security_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    distribution_id UUID NOT NULL REFERENCES distributions(id) ON DELETE CASCADE,
    group_type VARCHAR(20) NOT NULL DEFAULT 'read',
    group_name VARCHAR(255) NOT NULL
);

-- Jira component configurations (polymorphic owner)
CREATE TABLE jira_component_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID REFERENCES components(id) ON DELETE CASCADE,
    component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE,
    project_key VARCHAR(50),
    display_name VARCHAR(255),
    component_version_format JSONB,
    technical BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT chk_jira_config_owner CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    )
);

-- Component artifact IDs (for artifact resolution)
CREATE TABLE component_artifact_ids (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    group_pattern VARCHAR(500) NOT NULL,
    artifact_pattern VARCHAR(500) NOT NULL
);

-- Audit log (append-only)
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    action VARCHAR(20) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    old_value JSONB,
    new_value JSONB,
    change_diff JSONB,
    correlation_id VARCHAR(255)
);

-- Registry configuration (key-value store for field config, defaults, etc.)
CREATE TABLE registry_config (
    key VARCHAR(255) PRIMARY KEY,
    value JSONB NOT NULL DEFAULT '{}',
    updated_by VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Field overrides (per-field version ranges for components)
CREATE TABLE field_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    field_path VARCHAR(255) NOT NULL,
    version_range VARCHAR(255) NOT NULL,
    value JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Dependency mappings
CREATE TABLE dependency_mappings (
    alias VARCHAR(255) PRIMARY KEY,
    component_name VARCHAR(255) NOT NULL
);
