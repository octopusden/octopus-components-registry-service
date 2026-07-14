-- One component may own several TeamCity projects, one per release line
-- (distinguished by the TC `PROJECT_VERSION` parameter). Store that line marker
-- alongside the project id so the sync can keep one row per version.
-- Nullable: projects without a PROJECT_VERSION parameter keep NULL here.
ALTER TABLE component_teamcity_projects
    ADD COLUMN project_version VARCHAR(255);
