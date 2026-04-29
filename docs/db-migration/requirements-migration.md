# Migration Requirements (Git → DB)

## Status

**Draft** | Date: 2026-03-16

---

## Summary Table

| ID | Title | Priority | Layer | Status |
|----|-------|----------|-------|--------|
| MIG-001 | Migration preserves buildSystem from Defaults | High | integration-test | ✅ Tested |
| MIG-002 | Migration preserves nested build config | High | integration-test | ✅ Tested |
| MIG-003 | Migration preserves VCS settings | High | integration-test | ✅ Tested |
| MIG-004 | Migration preserves Distribution | High | integration-test | ✅ Tested |
| MIG-005 | Migration preserves Jira config | High | integration-test | ✅ Tested |
| MIG-006 | Migration preserves Escrow config | High | integration-test | ✅ Tested |
| MIG-007 | Migration is idempotent | Medium | integration-test | ✅ Tested |
| MIG-008 | Git and DB resolvers return identical data | High | integration-test | ✅ Tested |
| MIG-009 | component_source switches to "db" after migration | High | integration-test | ✅ Tested |
| MIG-010 | migrateDefaults preserves nested objects | Medium | integration-test | ✅ Tested |
| MIG-011 | Migration of 933 components without errors | High | e2e-test | ❌ Not tested |
| MIG-012 | Version-specific configs migrate correctly | High | integration-test | ✅ Tested |
| MIG-013 | Metadata migrates correctly | Medium | integration-test | ✅ Tested |
| MIG-014 | Archived/deprecated flags migrate | Medium | integration-test | ✅ Tested |
| MIG-015 | ArtifactId patterns migrate | Medium | integration-test | ✅ Tested |
| MIG-016 | Migration preserves top-level scalar defaults | High | integration-test | ✅ Tested |
| MIG-017 | Migration preserves build defaults | High | integration-test | ✅ Tested |
| MIG-018 | Migration preserves jira version format defaults | High | integration-test | ✅ Tested |
| MIG-019 | Migration preserves distribution defaults | High | integration-test | ✅ Tested |
| MIG-020 | Migration preserves escrow generation default | Medium | integration-test | ✅ Tested |
| MIG-021 | Version-range-only component inherits buildSystem from defaults | High | integration-test | ✅ Tested |
| MIG-022 | Migration preserves build-tools endpoint behavior | High | integration-test | ✅ Tested |
| MIG-023 | DB artifact resolution preserves version-specific matches for shared group IDs | High | unit-test, integration-test | ✅ Tested |
| MIG-024 | POST /rest/api/4/admin/migrate enforces IMPORT_DATA permission | High | integration-test | ✅ Tested |

---

## Requirements

### MIG-001: Migration preserves buildSystem from Defaults.groovy

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
When migrating a component that does not explicitly define `buildSystem`,
the value is taken from system defaults (`Defaults.groovy`).

**Preconditions:**
- `Defaults.groovy` contains `buildSystem = "MAVEN"`
- Component `TEST_COMPONENT` does not define `buildSystem` explicitly

**Acceptance criteria:**
1. After `POST /rest/api/4/admin/migrate-components`, component `TEST_COMPONENT` has `buildConfigurations[0].buildSystem = "MAVEN"`
2. `GET /rest/api/4/components/{id}` returns `buildSystem != null`

**Test method:** `MigrationIntegrationTest.MIG-001 migration preserves buildSystem from Defaults`

---

### MIG-002: Migration preserves nested build config (javaVersion, mavenVersion, gradleVersion)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Fields `javaVersion`, `mavenVersion`, `gradleVersion` from the component's DSL file
are saved into the `build_configurations` table during migration.

**Preconditions:**
- Component in Git DSL defines `build { javaVersion = "21"; mavenVersion = "3.9.6" }`

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `build.javaVersion = "21"`
2. `build.mavenVersion = "3.9.6"`
3. If `gradleVersion` is not specified in DSL, the field is `null` or the defaults value

**Test method:** `MigrationIntegrationTest.MIG-002 explicit build config`

---

### MIG-003: Migration preserves VCS settings (type, entries, branch, tag)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Component VCS settings — type (GIT/SVN), URL entries, branch pattern, tag pattern —
are correctly transferred from DSL into the `vcs_settings` table and related `vcs_entries`.

**Preconditions:**
- Component in DSL has `vcsSettings { vcsUrl = "ssh://git@..."; branch = "master"; tag = "release-${version}" }`

**Acceptance criteria:**
1. `GET /rest/api/4/components/{id}` returns `vcsSettings.type = "GIT"`
2. `vcsSettings.entries` contains at least one element with the correct URL
3. `vcsSettings.branch` and `vcsSettings.tag` match the DSL values

**Test method:** `MigrationIntegrationTest.MIG-003 VCS settings`

---

### MIG-004: Migration preserves Distribution (explicit, external, artifacts, securityGroups)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Distribution settings — `explicit`/`external` flags, artifact list,
security groups — are transferred into the `distributions` table and related tables.

**Preconditions:**
- Component in DSL has `distribution { explicit = true; external = true; securityGroups.add("sec-group-1") }`

**Acceptance criteria:**
1. `GET /rest/api/4/components/{id}` returns `distribution.explicit = true`
2. `distribution.external = true`
3. `distribution.securityGroups` contains `"sec-group-1"`
4. If `artifacts` are present, they also appear in the response

**Test method:** `MigrationIntegrationTest.MIG-004 distribution flags`

---

### MIG-005: Migration preserves Jira config (projectKey, displayName, versionFormat)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Jira configuration — `projectKey`, `displayName`, `versionFormat`, `technical` —
is transferred into the `jira_component_configs` table.

**Preconditions:**
- Component has `jira { projectKey = "MYPROJ"; displayName = "My Project" }`

**Acceptance criteria:**
1. `GET /rest/api/4/components/{id}` returns `jira.projectKey = "MYPROJ"`
2. `jira.displayName = "My Project"`
3. If `versionFormat` is set, it is present in the response
4. If `technical = true`, the flag is preserved

**Test method:** `MigrationIntegrationTest.MIG-005 Jira config`

---

### MIG-006: Migration preserves Escrow config (buildTask, reusable, generation)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Escrow configuration — `buildTask`, `reusable`, `generation` mode —
is transferred into the `escrow_configurations` table.

**Preconditions:**
- Component has `escrow { buildTask = "assemble"; reusable = true }`

**Acceptance criteria:**
1. `GET /rest/api/4/components/{id}` returns `escrow.buildTask = "assemble"`
2. `escrow.reusable = true`
3. If `generation` is not set, the value comes from defaults or is `null`

**Test method:** `MigrationIntegrationTest.MIG-006 Escrow config`

---

### MIG-007: Migration is idempotent (repeated call — skip)

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Repeated call to `POST /rest/api/4/admin/migrate-components` for already-migrated
components does not create duplicates and does not change existing data.

**Preconditions:**
- 933 components already migrated (all in `component_source` with `source = "db"`)

**Acceptance criteria:**
1. Repeated `POST /rest/api/4/admin/migrate-components` returns 200
2. Number of records in `components` has not changed
3. Field values of migrated components are identical before and after the repeated call
4. `GET /rest/api/4/admin/migration-status` shows the same numbers

**Test method:** `MigrationIntegrationTest.MIG-007 idempotency`

---

### MIG-008: Git and DB resolvers return identical data

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
For each migrated component, `DatabaseComponentRegistryResolver` and
`GitComponentRegistryResolver` must return semantically identical responses.
This is the key requirement for safe migration (see ADR-007).

**Preconditions:**
- Component is migrated from Git to DB
- Both resolvers are available

**Acceptance criteria:**
1. For a sample of components: `gitResolver.getComponent(name)` deep-equals `dbResolver.getComponent(name)`
2. Comparison includes: all scalar fields, build config, VCS, distribution, jira, escrow
3. Differences in audit fields (`createdAt`, `updatedAt`, `version`) are acceptable
4. If a discrepancy is found, the component stays on Git source

**Test method:** `MigrationIntegrationTest.MIG-008 Git and DB resolvers return identical data`

---

### MIG-009: component_source switches to "db" after migration

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
After successful migration of a component, the record in the `component_source` table
gets value `source = "db"`, and `ComponentRoutingResolver` starts routing
requests to `DatabaseComponentRegistryResolver`.

**Preconditions:**
- Component `TestComponent` is not yet migrated (source = "git" or absent)

**Acceptance criteria:**
1. Before migration: `ComponentRoutingResolver` uses Git resolver for `TestComponent`
2. After `POST /rest/api/4/admin/migrate-components`: `component_source` contains record `(TestComponent, db)`
3. `ComponentRoutingResolver` now uses DB resolver for `TestComponent`
4. `GET /rest/api/4/admin/migration-status` reflects increased `db` counter

**Test method:** `MigrationIntegrationTest.MIG-009 component_source switches to db`

---

### MIG-010: migrateDefaults preserves nested objects

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Endpoint `POST /rest/api/4/admin/migrate-defaults` parses `Defaults.groovy`
and saves nested objects (build defaults, escrow defaults, jira defaults)
into `registry_config` with key `component-defaults`.

**Preconditions:**
- `Defaults.groovy` contains nested structure: `build { buildSystem = "MAVEN"; javaVersion = "21" }`

**Acceptance criteria:**
1. After `POST /rest/api/4/admin/migrate-defaults` returns 200
2. `GET /rest/api/4/admin/component-defaults` returns JSON with `build.buildSystem = "MAVEN"`
3. Nested objects (`build`, `escrow`, `jira`) are present in the response
4. Scalar defaults fields (`copyright`, `releaseManager`) are also saved

**Test method:** `MigrationIntegrationTest.MIG-010 migrateDefaults preserves nested objects`

---

### MIG-011: Migration of 933 components without errors

**Priority:** High
**Test layer:** e2e-test
**Status:** ❌ Not tested

**Description:**
Full migration of all 933 components from the production Git repository
completes without errors. This is an end-to-end validation of the entire migration pipeline.

**Preconditions:**
- Git repository `components-registry` is available (tag `refs/tags/components-registry-1.9114`)
- PostgreSQL is running with a clean schema

**Acceptance criteria:**
1. `POST /rest/api/4/admin/migrate-components` returns 200
2. `GET /rest/api/4/admin/migration-status` returns `{"git": 0, "db": 933, "total": 933}`
3. Logs contain no migration-related `ERROR` or `WARN` entries
4. Execution time < 5 minutes

**Test method:** —

---

### MIG-012: Version-specific configs migrate correctly

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Components with version-specific configurations (DSL `"[1.0, 2.0)"` blocks)
create records in `field_overrides` with correct version ranges and values.

**Preconditions:**
- Component in DSL has a version-specific block, e.g.: `"[1.0, 2.0)" { build { buildSystem = "GRADLE" } }`

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}/overrides` returns override records
2. Each override has correct `versionRange`, `fieldPath`, `value`
3. Override for `build.buildSystem` with range `[1.0, 2.0)` has `value = "GRADLE"`
4. Ranges do not overlap for the same field (per-field non-overlap)

**Test method:** `MigrationIntegrationTest.MIG-012 version overrides`

---

### MIG-013: Metadata (releaseManager, securityChampion, copyright) migrates correctly

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Tier 3 metadata — `releaseManager`, `securityChampion`, `copyright` —
is correctly transferred from DSL into `Component` entity fields.

**Preconditions:**
- Component defines `releaseManager = "user1"` and `securityChampion = "user2"`

**Acceptance criteria:**
1. `GET /rest/api/4/components/{id}` returns `releaseManager = "user1"`
2. `securityChampion = "user2"`
3. If `copyright` is set in DSL or defaults, it is present in the response

**Test method:** `MigrationIntegrationTest.MIG-013 metadata`

---

### MIG-014: Archived/deprecated flags migrate

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
`archived` and `deprecated` flags are transferred from DSL to DB.
Archived components remain accessible via API but are flagged accordingly.

**Preconditions:**
- Component in DSL has `archived = true`

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `archived = true`
2. Component is present in `GET /rest/api/4/components` with filter `archived=true`
3. Component is absent from the default list (if default filter is `archived=false`)

**Test method:** `MigrationIntegrationTest.MIG-014 archived flag`

---

### MIG-015: ArtifactId patterns migrate

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
`artifactId` patterns from DSL (used for Maven coordinate lookups)
are transferred into the `component_artifact_ids` table.

**Preconditions:**
- Component defines `artifactId = ["org.example:my-artifact", "org.example:my-artifact-*"]`

**Acceptance criteria:**
1. After migration the `component_artifact_ids` table contains records for the component
2. `GET /rest/api/4/components/{id}` includes `artifactIds` in the response
3. Lookup by artifact `GET /rest/api/4/components/find-by-artifact?groupId=org.example&artifactId=my-artifact` finds the component

**Test method:** `MigrationIntegrationTest.MIG-015 artifactId patterns`

---

### MIG-016: Migration preserves top-level scalar defaults

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
For components that do not explicitly define certain top-level scalar fields,
the values from `Defaults.groovy` must be present after migration.
This covers the defaults-inheritance mechanism (as opposed to MIG-001 which only covers `buildSystem`).

**Defaults.groovy values:**
```groovy
system = "CLASSIC"
repositoryType = GIT
tag = '$module-$version'
releasesInDefaultBranch = true
solution = false
artifactId = ANY_ARTIFACT
```

**Preconditions:**
- `Defaults.groovy` contains the values listed above
- Component does NOT explicitly define any of these fields in its DSL file

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `system = "CLASSIC"`
2. `repositoryType = "GIT"`
3. `tag` contains `"$module-$version"` (or the resolved template)
4. `releasesInDefaultBranch = true`
5. `solution = false`
6. `artifactId` reflects `ANY_ARTIFACT` default

**Test method:** `MigrationIntegrationTest.MIG-016 scalar defaults`

---

### MIG-017: Migration preserves build defaults

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Components that do not explicitly define build configuration inherit build defaults
from `Defaults.groovy`. These inherited values must be present after migration.
This covers the defaults-inheritance mechanism (as opposed to MIG-002 which covers explicit values).

**Defaults.groovy values:**
```groovy
build {
    requiredTools = "BuildEnv"
    javaVersion = "1.8"
    mavenVersion = "3.6.3"
    gradleVersion = "LATEST"
}
```

**Preconditions:**
- `Defaults.groovy` contains the build defaults listed above
- Component does NOT explicitly define any build config in its DSL file

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `build.requiredTools = "BuildEnv"`
2. `build.javaVersion = "1.8"`
3. `build.mavenVersion = "3.6.3"`
4. `build.gradleVersion = "LATEST"`

**Test method:** `MigrationIntegrationTest.MIG-017 build defaults`

---

### MIG-018: Migration preserves jira version format defaults

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Jira version format templates from `Defaults.groovy` must be preserved for components
that do not explicitly define their own version formats.

**Defaults.groovy values:**
```groovy
jira {
    majorVersionFormat = '$major.$minor'
    releaseVersionFormat = '$major.$minor.$service'
    hotfixVersionFormat = '$major.$minor.$service-$fix'
    customer { versionFormat = '$versionPrefix-$baseVersionFormat' }
}
```

**Preconditions:**
- `Defaults.groovy` contains the jira version format defaults listed above
- Component does NOT explicitly define jira version formats in its DSL file

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `jira.majorVersionFormat = "$major.$minor"`
2. `jira.releaseVersionFormat = "$major.$minor.$service"`
3. `jira.hotfixVersionFormat = "$major.$minor.$service-$fix"`
4. `jira.customer.versionFormat = "$versionPrefix-$baseVersionFormat"`

**Test method:** `MigrationIntegrationTest.MIG-018 jira format defaults`

---

### MIG-019: Migration preserves distribution defaults

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Default distribution settings from `Defaults.groovy` must be preserved for components
that do not explicitly define distribution config.
This covers the defaults-inheritance mechanism (as opposed to MIG-004 which covers explicit values).

**Defaults.groovy values:**
```groovy
distribution {
    explicit = false
    external = true
    securityGroups { read = "Production Security" }
}
```

**Preconditions:**
- `Defaults.groovy` contains the distribution defaults listed above
- Component does NOT explicitly define distribution config in its DSL file

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `distribution.explicit = false`
2. `distribution.external = true`
3. `distribution.securityGroups.read` contains `"Production Security"`

**Test method:** `MigrationIntegrationTest.MIG-019 distribution defaults`

---

### MIG-020: Migration preserves escrow generation default

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Default `escrow.generation = AUTO` from `Defaults.groovy` must be preserved for components
that do not explicitly define escrow configuration.
This covers the defaults-inheritance mechanism (as opposed to MIG-006 which covers explicit escrow values).

**Defaults.groovy values:**
```groovy
escrow { generation = EscrowGenerationMode.AUTO }
```

**Preconditions:**
- `Defaults.groovy` contains `escrow.generation = AUTO`
- Component does NOT explicitly define escrow config in its DSL file

**Acceptance criteria:**
1. After migration `GET /rest/api/4/components/{id}` returns `escrow.generation = "AUTO"`
2. The value is distinguishable from `null` / absent

**Test method:** `MigrationIntegrationTest.MIG-020 escrow generation`

---

### MIG-021: Version-range-only component inherits buildSystem from defaults

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
A component that has only version-range blocks and no explicit component-level `buildSystem`
must still inherit `buildSystem` from `Defaults.groovy`. Component-level build fields such as
`javaVersion` must remain preserved.

**Preconditions:**
- `Defaults.groovy` contains `buildSystem = PROVIDED`
- Component defines `build { javaVersion = "1.8" }`
- Component has only version-range blocks and no `$ALL_VERSIONS` wrapper

**Acceptance criteria:**
1. After migration the component-level build configuration has `buildSystem = "PROVIDED"`
2. `javaVersion = "1.8"` is preserved
3. Version-range entries are still present in the migrated component

**Test method:** `MigrationIntegrationTest.MIG-021 version-range-only component inherits buildSystem from defaults`

---

### MIG-022: Migration preserves build-tools endpoint behavior

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
For a migrated component with explicit `build.tools` configuration, endpoint
`GET /rest/api/2/components/{component}/versions/{version}/build-tools?ignore-required=true`
must return the same JSON when the component is routed to Git and when it is routed to DB.

**Preconditions:**
- Component defines explicit `build.tools` in DSL
- Component is migrated to DB
- Component source can be switched between `git` and `db`

**Acceptance criteria:**
1. With source=`git`, the endpoint returns HTTP 200 and a non-empty build-tools array
2. With source=`db`, the endpoint returns HTTP 200 for the same component/version
3. Git and DB JSON responses are equal

**Test method:** `MigrationIntegrationTest.MIG-022 build-tools endpoint parity`

---

### MIG-023: DB artifact resolution preserves version-specific matches for shared group IDs

**Priority:** High
**Test layer:** unit-test, integration-test
**Status:** ✅ Tested
**Source task:** `/Users/pgorbachev/projects/ow/escrow-generator/tmp/crs_inventory_dm_03.62.30.02_1/agent_task.md`

**Description:**
When multiple components share the same Maven group ID, DB-backed `find-by-artifact`
must preserve Git resolver behavior and return the most specific version-aware match
instead of falling back to a generic component-level artifact pattern.

**Preconditions:**
- A generic component-level artifact pattern exists for the shared group ID
- A more specific version-range artifact mapping exists for the concrete component
- The requested artifact version falls into the version-specific range

**Acceptance criteria:**
1. `DatabaseComponentRegistryResolver.findComponentByArtifact(...)` returns the concrete component for the matching version-specific artifact
2. The generic component-level artifact mapping is not selected when a more specific version-specific match exists
3. After migration, DB-backed artifact lookup is identical to Git-backed artifact lookup for the same request

**Test method:** `DatabaseComponentRegistryResolverTest.MIG-023 DB resolver prefers version-specific artifact over generic match`; `MigrationIntegrationTest.MIG-023 version-specific artifact mapping parity`

---

### MIG-024: POST /rest/api/4/admin/migrate enforces IMPORT_DATA permission

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
The portal exposes a "Run migration" button on the `/admin` page (visible only
when an authenticated user has the `IMPORT_DATA` permission and has explicitly
enabled "Admin mode" in the footer). The button POSTs to
`/rest/api/4/admin/migrate`. UI gates are UX hints — the authoritative gate is
on CRS:

- The class-level `@PreAuthorize("@permissionEvaluator.canImport()")` on
  `AdminControllerV4`.
- The network-level matcher
  `.requestMatchers("/rest/api/4/**").authenticated()` in `WebSecurityConfig`.

This requirement pins both gates with an explicit security regression test so
that future refactors of the controller or security chain cannot silently
expose the migration endpoint.

**Preconditions:**
- `octopus-security-common` `BasePermissionEvaluator` maps `ROLE_ADMIN` to the
  `IMPORT_DATA` permission via `application.yml` (existing config).

**Acceptance criteria:**
1. `POST /rest/api/4/admin/migrate` without an `Authorization` header returns
   HTTP 401 with the JSON envelope produced by `jsonAuthenticationEntryPoint`.
2. `POST /rest/api/4/admin/migrate` with a valid JWT whose authorities do NOT
   include `IMPORT_DATA` returns HTTP 403 with the JSON envelope produced by
   `jsonAccessDeniedHandler`. `ImportService.migrate()` is not invoked.
3. `POST /rest/api/4/admin/migrate` with a valid JWT whose authorities include
   `IMPORT_DATA` returns HTTP 200 with a `FullMigrationResult` body, and
   `ImportService.migrate()` is invoked exactly once.

**Test method:** `AdminControllerV4SecurityTest.MIG-024 anonymous POST migrate returns 401`; `AdminControllerV4SecurityTest.MIG-024 editor JWT POST migrate returns 403`; `AdminControllerV4SecurityTest.MIG-024 admin JWT POST migrate returns 200 and runs migration once`.

**Out of scope:**
- Frontend disabled-button / Admin-mode-toggle behavior (covered by Vitest
  RTL on the portal).
- The actual content of `FullMigrationResult` (covered by existing
  `MigrationIntegrationTest`).
