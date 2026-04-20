# TD-002: Enable Flyway on All Environments and Remove columnDefinition Workarounds

## Status

Open

## Context

QA and other non-prod environments currently run with `ddl-auto=create` and `flyway.enabled=false`
(configured in `service-config/components-registry-service-cloud-qa.yml`). Hibernate creates the
schema directly from JPA entities, defaulting to `VARCHAR(255)` for all unmapped String columns.

This caused migration failures for 29 components whose DSL configurations contain
`artifact_pattern`, `group_pattern`, `build_task`, and similar fields exceeding 255 characters.

## Workaround Applied

Added `columnDefinition = "TEXT"` to the affected entity fields so that Hibernate generates TEXT
columns even without Flyway. The workaround is in place in the following entities:

- `ComponentArtifactIdEntity` — `groupPattern`, `artifactPattern`
- `DistributionArtifactEntity` — `groupPattern`, `artifactPattern`, `classifier`, `name`, `tag`
- `EscrowConfigurationEntity` — `buildTask`, `providedDependencies`
- `VcsSettingsEntryEntity` — `vcsPath`, `tag`, `branch`, `hotfixBranch`
- `BuildConfigurationEntity` — `buildFilePath`
- `VcsSettingsEntity` — `externalRegistry`

Flyway migration V3 (`V3__text_columns.sql`) already declares all of these columns as TEXT.
The `columnDefinition` annotations are therefore redundant once Flyway is enabled.

## What to Do

When promoting to production and stabilising the schema:

1. Switch all environments to `flyway.enabled=true` and `ddl-auto=validate` in `service-config`.
2. Remove all `columnDefinition = "TEXT"` annotations marked with `// see TD-002` from the entities
   listed above.
3. Verify that Flyway applies V1–V4 migrations cleanly on a fresh database.

## Out of Scope

- Changing the Flyway migration scripts themselves (V3 is correct as-is).
- Modifying the test profile (`application-test.yml`), which intentionally uses `create-drop` for
  fast isolated tests.

## Acceptance Criteria

- All environments use Flyway for schema management.
- No `columnDefinition = "TEXT"` annotations exist in entity classes.
- Migration of all components succeeds without failures on a fresh QA database.
