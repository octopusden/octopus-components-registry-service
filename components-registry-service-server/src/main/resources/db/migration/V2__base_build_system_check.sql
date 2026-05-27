-- UI-swift-sloth: BASE rows must declare a build_system.
--
-- Column-level NOT NULL would break the consolidated CHECK in V1
-- (line ~179, "row_type IN ('MARKER','RANGE_PRESENCE') → all 28 typed
-- scalars NULL"); a targeted "BASE → build_system IS NOT NULL" defends
-- the same invariant at the right layer. The service layer
-- (`ComponentManagementServiceImpl.createComponent`) is the user-visible
-- 400 path; this CHECK is defence-in-depth against direct DB writes,
-- future bulk-loaders, or service-layer regressions that bypass the
-- controller validation. MARKER / RANGE_PRESENCE / SCALAR_OVERRIDE
-- shapes are unchanged.
--
-- Forward-only Flyway migration: V1 stays byte-stable so its checksum
-- does not change on environments where V1 was already applied. This
-- file (V2) layers the new CHECK on top via `ALTER TABLE ... ADD
-- CONSTRAINT`.

ALTER TABLE component_configurations
    ADD CONSTRAINT chk_component_configurations_base_build_system
    CHECK (row_type <> 'BASE' OR build_system IS NOT NULL);
