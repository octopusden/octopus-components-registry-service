-- Source marker for audit_log rows: distinguishes runtime API events
-- from synthetic backfill rows produced by /rest/api/4/admin/migrate-history.
ALTER TABLE audit_log
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'api';

CREATE INDEX idx_audit_source ON audit_log(source);

-- One-row state for the git-history import. No resume support in v1:
-- any subsequent run requires reset=true.
CREATE TABLE git_history_import_state (
    import_key VARCHAR(64) PRIMARY KEY,
    target_ref VARCHAR(255) NOT NULL,
    target_sha VARCHAR(64) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
