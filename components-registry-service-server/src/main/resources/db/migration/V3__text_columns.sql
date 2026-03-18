-- Increase column sizes to TEXT for fields that may contain long values from DSL

ALTER TABLE vcs_settings_entries ALTER COLUMN tag TYPE TEXT;
ALTER TABLE vcs_settings_entries ALTER COLUMN branch TYPE TEXT;
ALTER TABLE vcs_settings_entries ALTER COLUMN hotfix_branch TYPE TEXT;

ALTER TABLE build_configurations ALTER COLUMN build_file_path TYPE TEXT;

ALTER TABLE escrow_configurations ALTER COLUMN build_task TYPE TEXT;

ALTER TABLE vcs_settings ALTER COLUMN external_registry TYPE TEXT;

ALTER TABLE distribution_artifacts ALTER COLUMN group_pattern TYPE TEXT;
ALTER TABLE distribution_artifacts ALTER COLUMN artifact_pattern TYPE TEXT;
ALTER TABLE distribution_artifacts ALTER COLUMN name TYPE TEXT;
ALTER TABLE distribution_artifacts ALTER COLUMN tag TYPE TEXT;

ALTER TABLE component_artifact_ids ALTER COLUMN group_pattern TYPE TEXT;
ALTER TABLE component_artifact_ids ALTER COLUMN artifact_pattern TYPE TEXT;
