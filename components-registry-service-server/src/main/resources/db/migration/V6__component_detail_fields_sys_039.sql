-- SYS-039: extend `components` with the six fields the §7.0 Wave 2 portal
-- editor needs in the General tab. All additive; existing rows get NULL /
-- empty-array defaults so the migration is metadata-only on Postgres 11+
-- (no table rewrite).
--
--   group_id                    Identity column — Maven groupId, used by
--                               releng tooling. Nullable.
--   release_manager             Ownership — single username, follows the
--                               same VARCHAR(255) pattern as component_owner.
--   security_champion           Ownership — single username, R* when the
--                               component is externally distributed.
--   copyright                   Metadata — longer free-form text (license /
--                               attribution snippet). TEXT, not VARCHAR.
--   releases_in_default_branch  Identity — boolean toggle, nullable so we
--                               can distinguish "unset" from "explicitly off".
--   labels                      Metadata — `text[]` matching the existing
--                               `system` column pattern, default empty array.

ALTER TABLE components
    ADD COLUMN group_id VARCHAR(255),
    ADD COLUMN release_manager VARCHAR(255),
    ADD COLUMN security_champion VARCHAR(255),
    ADD COLUMN copyright TEXT,
    ADD COLUMN releases_in_default_branch BOOLEAN,
    ADD COLUMN labels text[] NOT NULL DEFAULT '{}';
