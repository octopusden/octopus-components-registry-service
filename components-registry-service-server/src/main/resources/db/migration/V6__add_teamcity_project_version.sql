-- Replace the flat component_teamcity_projects table with a normalized model:
--   teamcity_project — distinct TeamCity projects (project_id is UNIQUE)
--   version_line     — links a component + release version to a teamcity_project
-- The TeamCity sync populates these; the v4 API reads them (ordered by project_id
-- for now). The previous flat table is dropped (no data carried over).
DROP TABLE IF EXISTS component_teamcity_projects;

CREATE TABLE teamcity_project (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE version_line (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id        UUID NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    version             VARCHAR(255),
    teamcity_project_id UUID NOT NULL REFERENCES teamcity_project(id)
);
CREATE INDEX idx_version_line_component  ON version_line(component_id);
CREATE INDEX idx_version_line_tc_project ON version_line(teamcity_project_id);
