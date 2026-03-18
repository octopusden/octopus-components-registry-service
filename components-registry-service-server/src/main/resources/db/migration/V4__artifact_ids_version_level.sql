-- Allow component_artifact_ids to reference either a component or a component version
-- (mirrors the dual-level pattern used by other entity tables)

ALTER TABLE component_artifact_ids ALTER COLUMN component_id DROP NOT NULL;

ALTER TABLE component_artifact_ids
    ADD COLUMN component_version_id UUID REFERENCES component_versions(id) ON DELETE CASCADE;

ALTER TABLE component_artifact_ids
    ADD CONSTRAINT component_artifact_ids_owner_check CHECK (
        (component_id IS NOT NULL AND component_version_id IS NULL) OR
        (component_id IS NULL AND component_version_id IS NOT NULL)
    );
