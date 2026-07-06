-- SYS-060: append-only journal of operational / service events (redeploys, data
-- migrations, TeamCity resync, portal validation sweeps). Unlike audit_log (which
-- tracks ENTITY changes), this table records JOB RUNS and DEPLOY markers so the
-- portal Admin "Events" tab can show a persistent history that survives pod
-- restarts (today those runs live only in an in-memory AtomicReference + logs).
--
-- One row per run, transitioned RUNNING -> COMPLETED/FAILED in place (matched by
-- correlation_id). STARTUP rows are written terminal (COMPLETED). A run whose pod
-- dies mid-flight is reconciled to FAILED on the next startup so it never hangs
-- RUNNING (SYS-060). detail is JSON (result counts / errorMessage), mirroring the
-- audit_log old_value/new_value convention: TEXT column + @JdbcTypeCode(JSON).
CREATE TABLE service_event (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(40) NOT NULL,   -- STARTUP | MIGRATION_COMPONENTS | MIGRATION_HISTORY | TEAMCITY_RESYNC | VALIDATION_SWEEP
    status          VARCHAR(20) NOT NULL,   -- RUNNING | COMPLETED | FAILED
    source          VARCHAR(20) NOT NULL,   -- crs | portal
    triggered_by    VARCHAR(255),           -- system | scheduler | <username>
    service_version VARCHAR(100),           -- build version, for STARTUP rows
    correlation_id  VARCHAR(255),           -- job id; the RUNNING row is updated in place by this key
    summary         TEXT,
    detail          TEXT,                   -- JSON (@JdbcTypeCode(SqlTypes.JSON)): result counters / errorMessage
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_service_event_started    ON service_event(started_at DESC);
CREATE INDEX idx_service_event_type       ON service_event(event_type);
CREATE INDEX idx_service_event_source     ON service_event(source);
CREATE INDEX idx_service_event_status     ON service_event(status);
CREATE INDEX idx_service_event_correlation ON service_event(correlation_id);
