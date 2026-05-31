# Migration Requirements (Git → DB)

Numbered MIG-NNN contracts registry, peer of `requirements-common.md` (SYS-NNN) and `requirements-resolver.md` (RES-NNN). Each entry is a contract referenced by tests, ADRs, and the Portal feature docs; the status column reflects test coverage, not workflow state. New requirements get a new MIG-NNN row; satisfied requirements stay in the table as a permanent record of what the migration guarantees.

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
| MIG-025 | Version-range-only component migrates root-level SYS-039 fields and jira to dedicated entity columns | High | integration-test | ✅ Tested |
| MIG-026 | POST /rest/api/4/admin/migrate-history backfills git history into audit_log | High | integration-test | ❌ Not tested |
| MIG-027 | POST /admin/migrate is async; 202 new / 409 re-run guard / GET /admin/migrate/job polling | High | integration-test | ✅ Tested |
| MIG-028 | Async migration job state survives pod restart | Medium | design | ❌ Open |
| MIG-029 | DB → EscrowModule round-trip preserves the absence of a default ALL_VERSIONS config for version-range-only components | High | integration-test | ❌ Not tested |
| MIG-030 | Polymorphic FK pairs removed; all per-version data on `component_configurations` | High | integration-test | ❌ Not tested |
| MIG-031 | JSONB `metadata` columns promoted to typed columns | High | unit-test | ❌ Not tested |
| MIG-032 | Reference dictionaries for `labels`, `systems`, `tools` | Medium | integration-test | ❌ Not tested |
| MIG-033 | Distribution split into four specialized child tables | Medium | unit-test | ❌ Not tested |
| MIG-034 | `component_doc_links` M:N with `major_version` | Medium | unit-test | ❌ Not tested |
| MIG-035 | Aggregator groups (`component_groups`) | High | integration-test | ❌ Not tested |
| MIG-036 | Per-attribute version-range overrides via Model A' | High | integration-test | ❌ Not tested |
| MIG-037 | Unified VCS model (no discriminator) | Medium | unit-test | ❌ Not tested |
| MIG-038 | Endpoint kill-list (5 endpoints dropped/stubbed) | Low | integration-test | ❌ Not tested |

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
4. Scalar defaults fields (e.g. `copyright`) are also saved. People fields
   (`componentOwner`, `releaseManager`, `securityChampion`) are **not** saved
   into `component-defaults` — `Defaults.groovy` never sets them, so they were
   removed from the defaults surface (see SYS-044).

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
1. `GET /rest/api/4/components/{id}` returns `releaseManager = ["user1"]`
   (v4 ordered `string[]`; the DSL CSV `"user1"` splits to a one-element list).
2. `securityChampion = ["user2"]`
3. If `copyright` is set in DSL or defaults, it is present in the response

> Note: legacy v1/v2/v3 still return these as the comma-joined `String`
> (`"user1"` / `"user2"`) — see SYS-044 in requirements-common.md.

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
   `IMPORT_DATA` succeeds: HTTP 202 (newly-started job) **or** 409 (existing
   `RUNNING` job — re-run guard) with a `MigrationJobResponse` body, and
   `MigrationJobService.startAsync()` is invoked exactly once. Full async
   contract details — including job lifecycle and polling — are pinned in
   `MIG-027`.

**Test method:** `AdminControllerV4SecurityTest.MIG-024 anonymous POST migrate returns 401`; `AdminControllerV4SecurityTest.MIG-024 editor JWT POST migrate returns 403`; `AdminControllerV4SecurityTest.MIG-024 admin JWT POST migrate returns 200 and runs migration once` (test method name predates the async migration in PR #156; it now asserts 202/409 — see test source).

> **Note (post-PR #156):** `POST /admin/migrate` is now asynchronous. This requirement still holds for the **auth gate** (401/403 checks), but the success-shape (criterion 3) now returns 202/409 + `MigrationJobResponse` rather than synchronous 200 + `FullMigrationResult`. The synchronous body is still reachable via `MigrationJobResponse.result` after polling `GET /admin/migrate/job` — see `MIG-027`.

**Out of scope:**
- Frontend disabled-button / Admin-mode-toggle behavior (covered by Vitest
  RTL on the portal).
- The actual content of `FullMigrationResult` (covered by existing
  `MigrationIntegrationTest`).

---

### MIG-025: Version-range-only component migrates root-level SYS-039 fields and jira to dedicated entity columns

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
A component whose DSL declares only version-range blocks (no `ALL_VERSIONS` wrapper —
e.g. `(,1.0.107)` and `[1.0.107,)`) inherits root-level properties (`labels`,
`releaseManager`, `securityChampion`, `groupId`, `jira`) into each version-range
config. `EscrowConfigurationLoader` does not synthesize an ALL_VERSIONS entry for
such components, so `toComponentEntity()` runs with `hasDefaultConfig = false`.
Before this fix the SYS-039 dedicated columns (`labels[]`, `release_manager`,
`security_champion`, `group_id`, `copyright`, `releases_in_default_branch`) were
guarded behind `hasDefaultConfig` and never populated, leaving the v4
`/components/{name}` response with empty/null values for these fields. The v4
`jiraComponentConfigs` response was also empty because the read-side flattening
only looked at component-level `jiraComponentConfigs`, which is intentionally
left empty for version-range-only components (per `buildJiraVersionRangesForComponent`
ALL_VERSIONS semantics).

> Note (SYS-044): `release_manager` / `security_champion` are no longer scalar
> columns. They are now ordered child tables (`component_release_managers` /
> `component_security_champions`) and v4 returns them as ordered `string[]`.
> The root-level-inheritance behaviour this requirement pins is unchanged —
> only the storage shape and the v4 field type changed. Legacy v1/v2/v3 still
> return the comma-joined `String`.

**Acceptance criteria:**
1. After migration of a version-range-only component:
   - `component_entity.labels` contains the root-level DSL labels
   - the `component_release_managers` / `component_security_champions` ordered
     child rows are populated from root-level DSL values (split from the
     comma-separated string, keep-first deduped); `group_id` / `copyright` /
     `releases_in_default_branch` are populated from root-level DSL values (or
     inherited defaults).
   - Each `component_version_entity.jira_component_configs` row contains the
     range-specific (root-inherited + per-range override) jira config.
2. v4 `GET /rest/api/4/components/{name}` returns:
   - `labels`, `releaseManager`, `securityChampion`, `groupId`,
     `releasesInDefaultBranch` populated — `releaseManager` / `securityChampion`
     as ordered `string[]` from their child tables, the rest from their columns.
   - `jiraComponentConfigs` non-empty — falls back to the deduplicated set of
     version-entity jira configs when component-level is empty.
3. v2 `GET /rest/api/2/components/{name}/versions/{ver}` continues to return
   correct `labels` / `releaseManager` / `securityChampion` for both
   freshly-migrated rows (read from dedicated columns) and pre-fix rows
   migrated with values in `metadata` (read via the `metadata` fallback in
   `toEscrowModuleConfig`).
4. `RES-001 / All Jira component version ranges` keeps emitting exactly one
   entry per `versionRange` (no spurious `ALL_VERSIONS` entry for
   version-range-only components).

**Test method:** `MigrationIntegrationTest.MIG-025 version-range-only component preserves root-level metadata fields` against `TEST_COMPONENT3` (range blocks `(,1.0.107)` and `[1.0.107,)`, root-level labels/releaseManager/securityChampion/groupId/jira). Asserts dedicated-column values via the v4 detail response. RES-001 unchanged-output regression covered by `ComponentsRegistryServiceControllerTest` and `DbBackedComponentsRegistryServiceControllerTest`.

**Out of scope:**
- Per-range jira surfacing in the v4 detail response: range-specific configs
  remain accessible via `versions[].jiraComponentConfigs`. The top-level
  `jiraComponentConfigs` is deduplicated by `(projectKey, displayName,
  componentVersionFormat)` so equivalent inherited configs collapse to one entry.
- Backfill of legacy `metadata` keys on rows migrated before SYS-039 — handled
  by re-running `POST /admin/migrate-components` after deploy. The metadata
  fallback in `toEscrowModuleConfig` preserves v2/v3 correctness in the interim.

---

### MIG-026: POST /rest/api/4/admin/migrate-history backfills git history into audit_log

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
The DB migration only captures runtime CRUD events from the moment the service is cut over to `source=db`. To preserve developer-visible history older than the cut-over, `POST /admin/migrate-history` replays the legacy DSL repository's git log into `audit_log`. Each historical commit that touches a known component becomes one synthetic audit row marked `source = 'git-history'` so that runtime UI (filters, history view) can blend or separate the two streams.

The endpoint is idempotent through a single-row state in `git_history_import_state`:
- An atomic `INSERT … ON CONFLICT DO NOTHING` claim under `import_key` decides whether this caller is the runner or a no-op observer.
- After the clone resolves the real tag/sha, the row is updated with `target_ref`, `target_sha`, and `status = COMPLETED`.
- A subsequent call with `reset=false` against an already-completed import is a no-op; `reset=true` clears the state and re-runs.

**Preconditions:**
- Legacy DSL git repository reachable from the service (same configuration as runtime resolver).
- Schema `V5__audit_source_and_history_state.sql` applied (creates `audit_log.source` column and `git_history_import_state` table).
- Caller authenticated with `IMPORT_DATA` permission (class-level `@PreAuthorize` on `AdminControllerV4`).

**Acceptance criteria:**
1. **First run** — `POST /admin/migrate-history?reset=false` against an empty `git_history_import_state` returns HTTP 200 with `HistoryImportResult { targetRef, targetSha, processedCommits, skippedNoGroovy, skippedParseError, skippedUnknownNames, auditRecords, durationMs }`. `auditRecords > 0` and equals the count of new rows in `audit_log` with `source = 'git-history'`.
2. **Idempotent re-run** — calling the same endpoint again with `reset=false` after a `COMPLETED` import is a no-op: returns 200 with `processedCommits = 0` and `auditRecords = 0`. No duplicate rows are added to `audit_log`.
3. **Reset re-run** — calling with `reset=true` clears state and re-imports. Audit rows from the previous run are not deleted (history is append-only); runtime is responsible for deduplicating based on `(timestamp, entity_id, action, source)` if needed.
4. **Optional `toRef`** — when supplied, the import targets the named ref (tag, branch, or sha) rather than `HEAD`. The resolved `targetSha` is recorded in the response and in `git_history_import_state.target_sha`.
5. **Skip counters** — commits that touch `.kts` only, fail to parse, or refer to unknown component names are counted in the corresponding `skippedNoGroovy` / `skippedParseError` / `skippedUnknownNames` fields and do not produce audit rows.
6. **Auth gate** — same shape as MIG-024: 401 anonymous, 403 for editor JWT, 200 for admin JWT (regression test exists as `migrate-history` security test, see `(#155)` PR title).

**Test method:** —

**Out of scope:**
- Backfilling pre-DSL data (e.g. wiki history, manual edits) — only what's reachable from the legacy git repo.
- A resume mode for partial failures (v1 requires `reset=true` to retry; explicit choice in V5 schema).
- UI presentation of `source = 'git-history'` rows — that's a Portal concern (see Portal `docs/features/audit-log.md` if/when written).

---

### MIG-027: POST /admin/migrate is async with 202/409 re-run guard and GET /admin/migrate/job polling

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested (PR #156, commit `c81026b`/`4d4abcb`)

**Description:**
A full Git→DB migration of ~933 components exceeds Portal's HTTP read timeout when run synchronously (the gateway returns 504 even though `ImportService` keeps working in the background). To support an interactive UX, `POST /rest/api/4/admin/migrate` runs on a background single-thread executor (`MigrationExecutorConfig`) and exposes per-component progress through `MigrationJobService`. The Portal SPA polls `GET /admin/migrate/job` to render a progress bar.

The wire shape is `MigrationJobResponse`:

```kotlin
data class MigrationJobResponse(
    val id: String,                  // UUID assigned at startAsync()
    val state: JobState,             // RUNNING | COMPLETED | FAILED
    val startedAt: Instant,
    val finishedAt: Instant?,        // null while RUNNING
    val total: Int,
    val migrated: Int,
    val failed: Int,
    val skipped: Int,
    val currentComponent: String?,   // populated during RUNNING; null after
    val errorMessage: String?,       // populated on state=FAILED
    val result: FullMigrationResult?, // populated on state=COMPLETED
)
```

Note: there is **no `CANCELLED` state** in `JobState` — only RUNNING, COMPLETED, FAILED. There is no cancel endpoint either; cancellation is out of scope for this contract.

**Preconditions:**
- Caller authenticated with `IMPORT_DATA` permission (covered by MIG-024 auth gate).
- `MigrationExecutorConfig` thread pool wired (single-thread `ExecutorService`).

**Acceptance criteria:**
1. **Newly-started job** — `POST /admin/migrate` against a fresh pod (or after a previous COMPLETED/FAILED job) returns **HTTP 202 Accepted** with body `MigrationJobResponse { state=RUNNING, id=<uuid>, startedAt=<now>, total=0|<known>, migrated=0, failed=0, skipped=0, currentComponent=null|<first> }`. The work proceeds on the background executor.
2. **Re-run guard (409)** — A second `POST /admin/migrate` while the first job is `RUNNING` returns **HTTP 409 Conflict** with the body of the existing job (same `id`, same `state=RUNNING`, advanced counters). `MigrationJobService.startAsync()` does not spawn a duplicate.
3. **Re-run after terminal state** — A new `POST /admin/migrate` after the previous job reached `COMPLETED` or `FAILED` returns 202 with a fresh `id` and replaces the slot. The previous result is no longer accessible via `GET /admin/migrate/job` after the new job claims the slot.
4. **Polling — running** — `GET /admin/migrate/job` while `state=RUNNING` returns 200 with the current `MigrationJobResponse`. `currentComponent` advances as `ImportService` walks the component list (driven by `MigrationProgressListener`); counters update.
5. **Polling — completed** — `GET /admin/migrate/job` after the job reaches `COMPLETED` returns 200 with `state=COMPLETED`, `finishedAt != null`, full counters, `currentComponent=null`, `errorMessage=null`, and `result: FullMigrationResult` populated.
6. **Polling — failed** — On `state=FAILED`, `errorMessage` is populated with a top-level message; `result` may be null or partial. The pod-side log carries the stack trace.
7. **No job since pod boot** — `GET /admin/migrate/job` returns **HTTP 404 Not Found** if `MigrationJobService.current() == null` (no migrate has been called since pod startup).

**Test method:** `MigrationJobIntegrationTest` (or similar) covering (1)-(7) end-to-end against a Spring Boot Test slice that exercises the real `WebSecurityConfig` chain and the executor. Auth-gate sub-cases reused from MIG-024.

**Out of scope:**
- Cancellation (no endpoint, no `CANCELLED` state).
- Cross-pod visibility / restart resilience (see MIG-028).
- Multi-job history (only the latest job is reachable; previous COMPLETED/FAILED is GC'd when a new job starts).

---

### MIG-028: Async migration job state survives pod restart

**Priority:** Medium
**Test layer:** design
**Status:** ❌ Open

**Description:**
`MigrationJobServiceImpl` currently stores state in an in-process `AtomicReference<MigrationJobState>` (single-pod scope). A pod restart during a long-running migration loses progress: the SPA's next poll yields `GET /admin/migrate/job` → 404, and the operator must re-run from scratch. The migration itself is idempotent at the per-component level (re-importing produces the same result), but the user-visible progress bar resets, and there is no audit-trail entry for the lost run.

The fix is design-deferred: pick one of (a) persist `MigrationJobState` rows to a DB table mirroring the `git_history_import_state` pattern (PR #151) so both pod restart and cross-pod visibility are covered; (b) on pod startup, reconstruct an approximation of state by scanning the latest `audit_log source='migration_job'` rows (requires writing such rows during the run, which is also missing today); (c) give up and document the limitation, relying on the operator to re-run.

The MIG-027 surface (DTO, status codes, paths) does **not** change as part of MIG-028 — only the persistence layer behind `MigrationJobService`. The Portal MigrationPanel has to handle the `404 → "no current job, please re-run"` branch regardless.

**Acceptance criteria:**
1. After a pod restart during `state=RUNNING`, the next `GET /admin/migrate/job` returns the last persisted state (most likely `state=FAILED` with an `errorMessage` such as `"job interrupted by pod restart"`), not 404, and not a phantom RUNNING.
2. After a pod restart during `state=COMPLETED` (job finished but the success was not yet observed by the SPA), the next `GET /admin/migrate/job` still returns `state=COMPLETED` with the original `result`.
3. Cross-pod: in a 2-pod deployment, `POST /admin/migrate` to pod A and `GET /admin/migrate/job` to pod B both observe the same `id`/`state`. (Today they do not.)
4. An audit_log entry is written for each migration start, end (success or fail), so retrospective inspection is possible even if state is GC'd.

**Out of scope:**
- The MIG-027 wire shape (unchanged).
- Cancellation (still out of scope).

**Suggested implementation sketch:**
- Add a `migration_job_state` table mirroring `git_history_import_state`: PK `id` (UUID), columns matching `MigrationJobState` fields, plus `updated_at`.
- Replace `AtomicReference` with a transactional read/write through that table.
- On pod startup, scan for `state=RUNNING` rows older than a sane threshold (e.g. 1 hour) and mark them `FAILED` with `errorMessage="interrupted by pod restart"`.

---

### MIG-029: DB → EscrowModule round-trip preserves the absence of a default ALL_VERSIONS config

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`EscrowConfigurationLoader` produces two shapes of `EscrowModule.moduleConfigurations` for a Git-DSL module: (1) a single `ALL_VERSIONS = "(,0),[0,)"` config when the DSL has no version-range blocks, or (2) only version-specific configs when the DSL has only `"<range>" { ... }` blocks (no top-level fields wrapped under `(,0),[0,)`). `EntityMappers.toComponentEntity()` correctly distinguishes the two shapes via a `hasDefaultConfig` local flag, but loses that distinction when persisting to the DB — `ComponentEntity` has no field that records "originally had ALL_VERSIONS row".

On the read-back path, `ComponentEntity.toEscrowModule()` (`EntityMappers.kt:42-57`) **unconditionally** synthesises a default `(,0),[0,)` config for every component, then appends `versions` on top. As a result, the DB resolver's `getComponents()` returns an extra spurious `(,0),[0,)` row for every version-range-only component. The compat-test smoke run discovered this on three of ten sampled production components (variants Map keys for those components on the v3 stand contain `(,0),[0,)`, on the prod main stand they do not).

This breaks v1/v2/v3 backward compatibility for any consumer that compares the set of variant ranges or looks up a per-range configuration.

**Acceptance criteria:**

`EscrowConfigurationLoader` produces exactly two `moduleConfigurations` shapes for any
DSL module: a single `ALL_VERSIONS` row when no version-range sections exist, or only
version-specific rows when at least one version-range block is present (top-level fields
in the latter case are absorbed into each version-specific config). No combined shape
exists — the loader collapses any DSL with both into the version-specific-only form.
The criteria therefore cover both real shapes:

1. After Git → DB migration of a version-range-only DSL component (e.g. `TEST_COMPONENT3` — only `"(,1.0.107)"` and `"[1.0.107,)"` blocks, no top-level wrapper), `dbResolver.getComponentById("TEST_COMPONENT3")!!.moduleConfigurations.map { it.versionRangeString }` does **not** contain `"(,0),[0,)"`. Set-equal to the original DSL ranges.
2. After migration of a default-only DSL component (e.g. component with only top-level fields, no version-range blocks), `getComponentById(...)!!.moduleConfigurations` is exactly `[ALL_VERSIONS]` (one row).
3. v3 list endpoint (`GET /rest/api/3/components`) returns `variants` keys exactly matching the DSL ranges per component (no extra `(,0),[0,)` entries for version-range-only components).

**Out of scope:**
- Re-migration of existing 933 production components (test-only environments are recreated from scratch — see project notes).
- Feature flag / rollout: this is a bug fix, not a switchable behaviour.

**Suggested implementation sketch:**
- Add a non-nullable boolean `has_default_config` column to `components` table (entity field default `false`; populated by `toComponentEntity` from the existing local `hasDefaultConfig` flag).
- `toEscrowModule` emits the synthetic default config only when `entity.hasDefaultConfig == true` OR `entity.versions.isEmpty()` (degenerate case: nothing to iterate, must emit at least one config so the module is non-empty).
- Test data already covers the version-range-only shape via `TEST_COMPONENT3`; add a focused MIG-029 test method in `MigrationIntegrationTest`.

**Test method:** `MigrationIntegrationTest.MIG-029 version-range-only component does not produce a synthetic ALL_VERSIONS config in toEscrowModule`

> **Note on suggested implementation sketch above:** v2 schema (ADR-014) supersedes the `has_default_config` proposal. The structural fix uses `component_configurations.is_synthetic_base BOOLEAN` on the base row; legacy variants-Map mappers skip rows with `is_synthetic_base = true`. See `schema-spec.md` §3.4.

---

### MIG-030: Polymorphic FK pairs removed; all per-version data on `component_configurations`

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

Schema v2 removes the `component_id` / `component_version_id` polymorphic FK pair from all configuration tables. Per-version data lives on `component_configurations` with a single FK to `components`. The `component_versions` table is gone.

**Acceptance criteria:**
1. `V1__schema.sql` contains no `component_version_id` column.
2. JPA entity inventory has no `ComponentVersionEntity` class.
3. Resolve queries succeed without referencing any polymorphic CHECK constraint.

---

### MIG-031: JSONB `metadata` columns promoted to typed columns

**Priority:** High
**Test layer:** unit-test
**Status:** ❌ Not tested

Seven JSONB columns from V1..V6 are replaced with explicit typed columns on `components` and `component_configurations`. Legitimate polymorphic JSON (audit_log, registry_config) is stored as TEXT with `@JdbcTypeCode(SqlTypes.JSON)`.

**Acceptance criteria:**
1. No `columnDefinition = "jsonb"` annotations remain on any entity.
2. `metadata["X"]` field accesses replaced with typed column reads in mappers and services.
3. v1-v3 API responses for unchanged components match a prod-aligned fixture suite byte-for-byte.

---

### MIG-032: Reference dictionaries for `labels`, `systems`, `tools`

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

After migration, three dictionary tables exist:
- `labels` — seeded from installation's `validation-config.yaml`.
- `systems` — auto-discovered from DSL `system = "X,Y"` values.
- `tools` — auto-discovered from DSL top-level `Tools { ToolName { ... } ... }` block.

Component-to-dictionary relationships via M:N junctions (`component_labels`, `component_systems`, `component_required_tools`).

**Acceptance criteria:**
1. After migration, `systems` contains exactly the union of distinct `system` tokens from DSL.
2. `tools` contains exactly the tools defined by `Tools { ... }` blocks.
3. `labels` matches `validation-config.yaml` content.
4. Component create/update via v4 rejects unknown label/system/tool references with 400.

---

### MIG-033: Distribution split into four specialized child tables

**Priority:** Medium
**Test layer:** unit-test
**Status:** ❌ Not tested

DSL `distribution { GAV, docker, DEB, RPM, securityGroups }` decomposes into four child tables of `component_configurations` plus `distribution_security_groups` on `components`. Per-family `sort_order` preserves DSL CSV order within each family; mapper concatenates families canonically (Maven, then file-URL) for v1-v3 responses.

**Acceptance criteria:**
1. DSL `GAV = "g:a:ext:cls, file://url?artifactId=X"` produces 1 row in `distribution_maven_artifacts` (sort_order=0) and 1 in `distribution_file_url_artifacts` (sort_order=0).
2. DSL `docker = "img1, img2:flavor"` produces 2 rows in `distribution_docker_images` with `flavor` set only on the second (sort_order 0, 1).
3. v1-v3 `GAV` response CSV recomposes correctly from per-family ordered reads.

---

### MIG-034: `component_doc_links` M:N with `major_version`

**Priority:** Medium
**Test layer:** unit-test
**Status:** ❌ Not tested

DSL `doc { component = "X"; majorVersion = "Y" }` maps to a row in `component_doc_links`. Schema supports multiple links per component (v4 API may create more even though DSL allows one block per component).

**Acceptance criteria:**
1. Migration of a component with `doc { component = "X" }` produces exactly one `component_doc_links` row.
2. v1-v3 mapper for `doc: { component, majorVersion }` applies major-version rule: for requested version `X.Y.Z`, pick row WHERE `major_version = 'X.Y'`; else WHERE `major_version IS NULL`; else null.
3. v4 API exposes the full list as `docs[]`.

---

### MIG-035: Aggregator groups (`component_groups`)

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

DSL nested `components { ... }` block creates a `component_groups` row. Detection rule classifies as REAL (own valid vcsUrl) or FAKE (no/placeholder vcsUrl or fake artifactId marker).

**Acceptance criteria:**
1. Standalone component (no nested block) produces 0 rows in `component_groups`; its `components.component_group_id IS NULL`.
2. REAL aggregator produces 1 `component_groups` row with `is_fake = false` + 1 `components` row for the aggregator + N rows for sub-components, all linked to the same group.
3. FAKE aggregator (artifactId or vcsUrl matches fake markers) produces 1 `component_groups` row with `is_fake = true` + N sub-component rows linked to the group; NO `components` row for the aggregator itself.
4. v1-v3 responses do not expose group membership.

---

### MIG-036: Per-attribute version-range overrides via Model A'

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

DSL version-range blocks produce override rows on `component_configurations`. Each scalar attribute change is a separate row (`overridden_attribute = 'aspect.field'`); each child-collection replacement is a marker row.

**Acceptance criteria:**
1. DSL `"[1,2)" { build { javaVersion = "11" } }` produces one scalar override row with `overridden_attribute = 'build.javaVersion'`, `java_version = '11'`, all other typed columns NULL.
2. DSL `"[1,2)" { vcsSettings { "name1" { ... } } }` produces one marker row with `overridden_attribute = 'vcs.settings'`, all typed scalars NULL, plus corresponding `vcs_settings_entries` child rows.
3. Resolve at version V applies the matching override per attribute; falls back to base row values for non-overridden fields.
4. Transitional non-overlap constraint enforced at create/update: partial range overlap rejected with 400.

---

### MIG-037: Unified VCS model

**Priority:** Medium
**Test layer:** unit-test
**Status:** ❌ Not tested

All VCS data lives in `vcs_settings_entries`. `component_configurations` has no VCS columns.

**Acceptance criteria:**
1. SINGLE-VCS DSL component produces 1 row in `vcs_settings_entries` with `name IS NULL`.
2. MULTI-VCS DSL component produces N rows with `name` populated.
3. v1-v3 API `vcs.type` mapper computes correctly: `EXTERNAL` when `components.vcs_external_registry IS NOT NULL`; `null` when no entries; `GIT` (or repository_type) otherwise.

---

### MIG-038: Endpoint kill-list

**Priority:** Low
**Test layer:** integration-test
**Status:** ❌ Not tested

Five effectively-dead endpoints (≤2 calls in 2 production days) are removed or stubbed per `schema-spec.md` §5.1.

**Acceptance criteria:**
1. `GET /rest/api/3/components` returns 410 Gone.
2. `PUT /rest/api/2/components-registry/service/updateCache` returns 410 Gone (matches deployed behaviour).
3. `GET /rest/api/2/components` (no params), `GET /rest/api/2/projects/{k}/jira-component-version-ranges`, `GET /rest/api/1/components` (no filter) each return 200 with empty array.
