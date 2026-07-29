-- Stored TeamCity validation findings, one row per (project_id, type). Only WARNING/ERROR
-- results are persisted (OK / NOT_APPLICABLE are not stored); latest-only per project (each run
-- replaces that project's rows). The (project_id, type) pair is the stable identity of a finding.
CREATE TABLE teamcity_validation (
    project_id VARCHAR(255)             NOT NULL,
    type       VARCHAR(64)              NOT NULL,   -- e.g. USES_OLD_JAVA_VERSION
    status     VARCHAR(32)              NOT NULL,   -- WARNING | ERROR
    message    TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, type)
);
