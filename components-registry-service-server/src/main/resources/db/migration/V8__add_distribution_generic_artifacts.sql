CREATE TABLE distribution_generic_artifacts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_configuration_id  UUID NOT NULL REFERENCES component_configurations(id) ON DELETE CASCADE,
    url                         TEXT NOT NULL,
    sort_order                  INT  NOT NULL DEFAULT 0
);
CREATE INDEX idx_dist_generic_config ON distribution_generic_artifacts(component_configuration_id);

ALTER TABLE component_configurations
    DROP CONSTRAINT component_configurations_check;

ALTER TABLE component_configurations
    ADD CONSTRAINT component_configurations_row_type_taxonomy_check CHECK (
        (row_type IN ('BASE', 'RANGE_PRESENCE') AND overridden_attribute IS NULL)
        OR (row_type = 'MARKER'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute IN (
                'vcs.settings',
                'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages',
                'distribution.generic',
                'build.requiredTools', 'build.buildTools',
                'group-artifact-pattern'
            ))
        OR (row_type = 'SCALAR_OVERRIDE'
            AND overridden_attribute IS NOT NULL
            AND overridden_attribute NOT IN (
                'vcs.settings',
                'distribution.maven', 'distribution.fileUrl',
                'distribution.docker', 'distribution.packages',
                'distribution.generic',
                'build.requiredTools', 'build.buildTools',
                'group-artifact-pattern'
            ))
    );
