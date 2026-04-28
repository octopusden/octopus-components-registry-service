-- Source marker for audit_log rows: distinguishes runtime API events
-- from synthetic backfill rows produced by /rest/api/4/admin/migrate-history.
-- NOT NULL DEFAULT is metadata-only on PostgreSQL 11+ (no table rewrite).
--
-- Note: the index is a plain CREATE INDEX, which takes a short
-- AccessExclusiveLock. `audit_log` is expected to be small when this
-- migration lands (backfill has not run yet). If this migration is ever
-- replayed against a large audit_log, run `CREATE INDEX CONCURRENTLY
-- idx_audit_source_new ON audit_log(source);` out-of-band, then
-- `DROP INDEX idx_audit_source; ALTER INDEX idx_audit_source_new RENAME TO
-- idx_audit_source;` to avoid stalling writes. A Flyway in-migration
-- CONCURRENTLY variant deadlocks against Flyway's own metadata transaction.
ALTER TABLE audit_log
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'api';

CREATE INDEX idx_audit_source ON audit_log(source);

-- One-row state for the git-history import. No resume support in v1:
-- any subsequent run requires reset=true. target_ref/target_sha are filled
-- with empty strings by the atomic INSERT claim and updated once the clone
-- resolves the real tag/sha.
CREATE TABLE git_history_import_state (
    import_key VARCHAR(64) PRIMARY KEY,
    target_ref VARCHAR(255) NOT NULL,
    target_sha VARCHAR(64) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
