-- SYS-062: user feedback / "report a problem" submissions with optional screenshot
-- attachments. Unlike audit_log (entity changes) and service_event (job runs), this
-- table stores free-form USER-submitted reports with their own lifecycle
-- (NEW -> IN_PROGRESS -> RESOLVED), viewable only by admins (IMPORT_DATA).
--
-- Screenshots are stored as bytea in feedback_attachment (one row per file); the
-- portal transports them base64-in-JSON and the service decodes to raw bytes after
-- validating magic-bytes / size / count. detail is JSON (@JdbcTypeCode(SqlTypes.JSON)):
-- user-agent and other diagnostic context, mirroring the audit_log/service_event
-- convention (TEXT column + JSON mapping).
CREATE TABLE feedback (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(20) NOT NULL,   -- BUG | IDEA | QUESTION
    status       VARCHAR(20) NOT NULL DEFAULT 'NEW', -- NEW | IN_PROGRESS | RESOLVED
    title        TEXT,
    message      TEXT NOT NULL,
    submitted_by VARCHAR(255),           -- Keycloak preferred_username, or 'system'
    page_url     TEXT,                   -- SPA route the report was filed from
    app_version  VARCHAR(100),           -- portal build version, for diagnostics
    detail       TEXT,                   -- JSON (@JdbcTypeCode(SqlTypes.JSON)): user-agent etc.
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE,
    updated_by   VARCHAR(255),           -- who last changed the status
    CONSTRAINT chk_feedback_type   CHECK (type   IN ('BUG', 'IDEA', 'QUESTION')),
    CONSTRAINT chk_feedback_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED'))
);

CREATE INDEX idx_feedback_status  ON feedback(status);
CREATE INDEX idx_feedback_type    ON feedback(type);
CREATE INDEX idx_feedback_created ON feedback(created_at DESC);

CREATE TABLE feedback_attachment (
    id           BIGSERIAL PRIMARY KEY,
    feedback_id  BIGINT NOT NULL REFERENCES feedback(id) ON DELETE CASCADE,
    filename     TEXT,
    content_type VARCHAR(100),           -- server-normalized MIME (image/png|image/jpeg)
    size_bytes   INTEGER,
    data         BYTEA NOT NULL
);

CREATE INDEX idx_feedback_attachment_feedback ON feedback_attachment(feedback_id);
