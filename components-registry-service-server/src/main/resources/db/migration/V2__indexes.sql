-- Components indexes
CREATE INDEX idx_components_archived ON components(archived);
CREATE INDEX idx_components_product_type ON components(product_type);
CREATE INDEX idx_components_system ON components USING GIN(system);
CREATE INDEX idx_components_metadata ON components USING GIN(metadata);
CREATE INDEX idx_components_parent ON components(parent_component_id);

-- Component versions indexes
CREATE INDEX idx_component_versions_component ON component_versions(component_id);

-- Build configurations indexes
CREATE INDEX idx_build_config_component ON build_configurations(component_id);
CREATE INDEX idx_build_config_version ON build_configurations(component_version_id);

-- Escrow configurations indexes
CREATE INDEX idx_escrow_config_component ON escrow_configurations(component_id);
CREATE INDEX idx_escrow_config_version ON escrow_configurations(component_version_id);

-- VCS settings indexes
CREATE INDEX idx_vcs_settings_component ON vcs_settings(component_id);
CREATE INDEX idx_vcs_settings_version ON vcs_settings(component_version_id);
CREATE INDEX idx_vcs_entries_settings ON vcs_settings_entries(vcs_settings_id);

-- Distributions indexes
CREATE INDEX idx_distributions_component ON distributions(component_id);
CREATE INDEX idx_distributions_version ON distributions(component_version_id);
CREATE INDEX idx_dist_artifacts_distribution ON distribution_artifacts(distribution_id);
CREATE INDEX idx_dist_security_distribution ON distribution_security_groups(distribution_id);

-- Jira configs indexes
CREATE INDEX idx_jira_config_component ON jira_component_configs(component_id);
CREATE INDEX idx_jira_config_version ON jira_component_configs(component_version_id);
CREATE INDEX idx_jira_config_project_key ON jira_component_configs(project_key);

-- Component artifact IDs indexes
CREATE INDEX idx_artifact_ids_component ON component_artifact_ids(component_id);
CREATE INDEX idx_artifact_ids_group ON component_artifact_ids(group_pattern);

-- Audit log indexes
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_changed_at ON audit_log(changed_at);
CREATE INDEX idx_audit_changed_by ON audit_log(changed_by);
CREATE INDEX idx_audit_correlation ON audit_log(correlation_id);

-- Field overrides indexes
CREATE INDEX idx_field_overrides_component ON field_overrides(component_id);
CREATE INDEX idx_field_overrides_field ON field_overrides(component_id, field_path);

-- Component source index
CREATE INDEX idx_component_source ON component_source(source);
