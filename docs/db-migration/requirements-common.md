# Common System Requirements

## Status

**Draft** | Date: 2026-03-16

---

## Summary Table

| ID | Title | Priority | Layer | Status |
|----|-------|----------|-------|--------|
| SYS-001 | GET /components returns paginated list | High | integration-test | ❌ Not tested |
| SYS-002 | GET /components/{id} returns full tree | High | integration-test | ❌ Not tested |
| SYS-003 | PATCH updates scalar fields | High | integration-test | ❌ Not tested |
| SYS-004 | PATCH with nested buildConfiguration | High | integration-test | ❌ Not tested |
| SYS-005 | PATCH with nested vcsSettings (entries replace-all) | High | integration-test | ❌ Not tested |
| SYS-006 | PATCH with nested distribution | High | integration-test | ❌ Not tested |
| SYS-007 | PATCH with nested jiraComponentConfig | High | integration-test | ❌ Not tested |
| SYS-008 | PATCH with nested escrowConfiguration | High | integration-test | ❌ Not tested |
| SYS-009 | Optimistic locking — stale version rejected | High | integration-test | ❌ Not tested |
| SYS-010 | @Version increments on child-only changes | High | integration-test | ❌ Not tested |
| SYS-011 | PATCH response contains current version | High | integration-test | ❌ Not tested |
| SYS-012 | Field overrides — POST creates override | Medium | integration-test | ❌ Not tested |
| SYS-013 | Field overrides — GET list | Medium | integration-test | ❌ Not tested |
| SYS-014 | Field overrides — PATCH updates range/value | Medium | integration-test | ❌ Not tested |
| SYS-015 | Field overrides — DELETE (204) | Medium | integration-test | ❌ Not tested |
| SYS-016 | Field override PATCH does not null value when updating only range | High | integration-test | ❌ Not tested |
| SYS-017 | GET /meta/owners — unique owners | Medium | integration-test | ❌ Not tested |
| SYS-018 | POST /admin/migrate-defaults — nested structure | Medium | integration-test | ❌ Not tested |
| SYS-019 | Audit log on component UPDATE | Medium | integration-test | ❌ Not tested |
| SYS-020 | Filtering by system, archived, search | Medium | integration-test | ❌ Not tested |
| SYS-021 | UI: Component list without JS errors | High | e2e-test | ❌ Not tested |
| SYS-022 | UI: Component detail with tabs | High | e2e-test | ❌ Not tested |
| SYS-023 | UI: Navigation between pages | High | e2e-test | ❌ Not tested |
| SYS-024 | UI: Editable tabs have Save button | Medium | e2e-test | ❌ Not tested |
| SYS-025 | DatabaseComponentRegistryResolver applies field overrides | High | integration-test | ❌ Not tested |
| SYS-026 | Flyway-managed PostgreSQL schema passes Hibernate validate | High | integration-test | ✅ Tested |
| SYS-027 | ft-db profile supports writes against jsonb columns | High | integration-test | ✅ Tested |
| SYS-028 | v4 API supports component rename | High | integration-test | ✅ Tested |
| SYS-029 | Renamed-away name no longer resolvable via v1/v2/v3 under ft-db | High | integration-test | ✅ Tested |
| SYS-030 | DistributionEntity round-trips groupId-only GAV without `:null` suffix | High | unit-test | ✅ Tested |
| SYS-031 | DistributionEntity round-trips multi-image docker coordinates verbatim | High | unit-test | ✅ Tested |
| SYS-032 | ComponentSourceRegistry reads reflect cross-pod DB changes on every call | High | integration-test | ✅ Tested |
| SYS-033 | GET /rest/api/4/info returns build name and version, anonymous access | Medium | integration-test | ✅ Tested |
| SYS-034 | GET /auth/me returns current user (username, roles, groups), authenticated | Medium | integration-test | ❌ Not tested |
| SYS-035 | GET /components?owner=&lt;username&gt; filters by componentOwner exact match | High | integration-test | ✅ Tested |
| SYS-036 | GET /audit/recent accepts entityType/entityId/changedBy/source/action/from/to filter params | High | integration-test | ✅ Tested |
| SYS-037 | v4 CRUD API for dependency mappings (alias → componentName) | Medium | integration-test | ❌ Not implemented |

---

## Requirements

### SYS-001: GET /components returns paginated list

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Endpoint `GET /rest/api/4/components` returns a paginated list of components
with pagination metadata (page, size, totalElements, totalPages).

**Preconditions:**
- Database contains >= 50 components

**Acceptance criteria:**
1. `GET /rest/api/4/components?page=0&size=20` returns 200
2. Response contains `content` (array), `totalElements`, `totalPages`, `number`, `size`
3. `content.length <= 20`
4. `totalElements >= 50`
5. Components are sorted by `name` (default)

**Test method:** —

---

### SYS-002: GET /components/{id} returns full tree

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Endpoint `GET /rest/api/4/components/{id}` returns the full component tree,
including all nested objects: build, VCS, distribution, jira, escrow, overrides.

**Preconditions:**
- Component with ID `{id}` exists and has populated nested configurations

**Acceptance criteria:**
1. HTTP status 200
2. Response contains scalar fields: `id`, `name`, `displayName`, `componentOwner`, `version`
3. Response contains nested objects: `build`, `vcsSettings`, `distribution`, `jira`, `escrow`
4. For a non-existent ID, returns 404

**Test method:** —

---

### SYS-003: PATCH updates scalar fields

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`PATCH /rest/api/4/components/{id}` with JSON Merge Patch (RFC 7396)
updates only the provided scalar fields; others remain unchanged.

**Preconditions:**
- Component exists with `displayName = "Old Name"`, `system = "CLASSIC"`

**Acceptance criteria:**
1. `PATCH` with `{"displayName": "New Name", "version": N}` returns 200
2. Response contains `displayName = "New Name"`
3. `system` remains `"CLASSIC"` (not provided — not changed)
4. `null` in request resets value: `{"displayName": null}` → `displayName` becomes `null` or default
5. `version` in response is incremented

**Test method:** —

---

### SYS-004: PATCH with nested buildConfiguration

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
PATCH can update the nested `build` object — `buildSystem`, `javaVersion`,
`mavenVersion`, `gradleVersion`.

**Preconditions:**
- Component has `build.javaVersion = "11"`

**Acceptance criteria:**
1. `PATCH` with `{"build": {"javaVersion": "21"}, "version": N}` returns 200
2. `build.javaVersion = "21"` in response
3. `build.buildSystem` unchanged (absent from request)
4. `build.mavenVersion` unchanged

**Test method:** —

---

### SYS-005: PATCH with nested vcsSettings (entries replace-all)

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
When PATCHing with `vcsSettings.entries`, the entries array is fully replaced
(replace-all semantics), not merged with existing records.

**Preconditions:**
- Component has 2 VCS entries: `[url1, url2]`

**Acceptance criteria:**
1. `PATCH` with `{"vcsSettings": {"entries": [{"url": "url3"}]}, "version": N}` returns 200
2. `vcsSettings.entries` contains only `[url3]` (not `[url1, url2, url3]`)
3. Scalar fields of `vcsSettings` (type, branch, tag) unchanged if not provided

**Test method:** —

---

### SYS-006: PATCH with nested distribution

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
PATCH updates the nested `distribution` object — `explicit`, `external`,
`securityGroups`, `artifacts`.

**Preconditions:**
- Component has `distribution.explicit = false`

**Acceptance criteria:**
1. `PATCH` with `{"distribution": {"explicit": true}, "version": N}` returns 200
2. `distribution.explicit = true`
3. Other distribution fields unchanged

**Test method:** —

---

### SYS-007: PATCH with nested jiraComponentConfig

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
PATCH updates the nested `jira` object — `projectKey`, `displayName`,
`versionFormat`, `technical`.

**Preconditions:**
- Component has `jira.projectKey = "OLD"`

**Acceptance criteria:**
1. `PATCH` with `{"jira": {"projectKey": "NEW"}, "version": N}` returns 200
2. `jira.projectKey = "NEW"`
3. `jira.displayName` unchanged

**Test method:** —

---

### SYS-008: PATCH with nested escrowConfiguration

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
PATCH updates the nested `escrow` object — `buildTask`, `reusable`, `generation`.

**Preconditions:**
- Component has `escrow.reusable = false`

**Acceptance criteria:**
1. `PATCH` with `{"escrow": {"reusable": true}, "version": N}` returns 200
2. `escrow.reusable = true`
3. `escrow.buildTask` unchanged

**Test method:** —

---

### SYS-009: Optimistic locking — stale version rejected

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
PATCH with a stale `version` value returns 409 Conflict,
preventing the lost update problem.

**Preconditions:**
- Component has `version = 5`
- Client A read the component with `version = 5`
- Client B updated the component → `version` became `6`

**Acceptance criteria:**
1. Client A sends `PATCH` with `{"displayName": "X", "version": 5}`
2. Server returns 409 Conflict
3. Response contains a version conflict message
4. Component data unchanged (Client B's update preserved)

**Test method:** —

---

### SYS-010: @Version increments on child-only changes

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Changing only a nested object (e.g., only `build.javaVersion`) must increment
the parent component's `@Version`, so that optimistic locking works correctly
for any changes.

**Preconditions:**
- Component has `version = 5`, `build.javaVersion = "11"`

**Acceptance criteria:**
1. `PATCH` with `{"build": {"javaVersion": "21"}, "version": 5}` returns 200
2. Response contains `version = 6` (incremented)
3. Repeated `PATCH` with `{"build": {"javaVersion": "17"}, "version": 5}` returns 409

**Test method:** —

---

### SYS-011: PATCH response contains current version

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Response from PATCH always contains the current `version` value after the update.
This allows the client to immediately perform another PATCH without an extra GET.

**Preconditions:**
- Component has `version = 5`

**Acceptance criteria:**
1. `PATCH` with `{"displayName": "X", "version": 5}` returns 200
2. Response contains `version = 6`
3. Client can immediately execute `PATCH` with `{"displayName": "Y", "version": 6}` — 200

**Test method:** —

---

### SYS-012: Field overrides — POST creates override

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`POST /rest/api/4/components/{id}/overrides` creates a new field override
with the specified `fieldPath`, `versionRange`, and `value`.

**Preconditions:**
- Component exists without overrides for field `build.buildSystem`

**Acceptance criteria:**
1. `POST` with `{"fieldPath": "build.buildSystem", "versionRange": "[1.0, 2.0)", "value": "GRADLE"}` returns 201
2. Response contains the created override with `id`, `fieldPath`, `versionRange`, `value`
3. `GET /rest/api/4/components/{id}/overrides` includes the new override

**Test method:** —

---

### SYS-013: Field overrides — GET list

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`GET /rest/api/4/components/{id}/overrides` returns all field overrides for a component.

**Preconditions:**
- Component has 3 override records for different fields

**Acceptance criteria:**
1. HTTP status 200
2. Response is an array of 3 objects
3. Each object contains: `id`, `fieldPath`, `versionRange`, `value`
4. For a component without overrides, returns empty array `[]`

**Test method:** —

---

### SYS-014: Field overrides — PATCH updates range/value

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`PATCH /rest/api/4/components/{id}/overrides/{overrideId}` updates
`versionRange` and/or `value` of an existing override.

**Preconditions:**
- Override exists: `fieldPath = "build.buildSystem"`, `versionRange = "[1.0, 2.0)"`, `value = "GRADLE"`

**Acceptance criteria:**
1. `PATCH` with `{"versionRange": "[1.0, 3.0)"}` returns 200
2. Override has `versionRange = "[1.0, 3.0)"` and `value = "GRADLE"` (value unchanged)
3. `PATCH` with `{"value": "MAVEN"}` returns 200
4. Override has `value = "MAVEN"` and `versionRange = "[1.0, 3.0)"` (range unchanged)

**Test method:** —

---

### SYS-015: Field overrides — DELETE (204)

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`DELETE /rest/api/4/components/{id}/overrides/{overrideId}` deletes an override.

**Preconditions:**
- Override exists with ID `{overrideId}`

**Acceptance criteria:**
1. `DELETE` returns 204 No Content
2. `GET /rest/api/4/components/{id}/overrides` does not contain the deleted override
3. Repeated `DELETE` returns 404

**Test method:** —

---

### SYS-016: Field override PATCH does not null value when updating only range

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
When PATCHing an override with only `versionRange` (without `value`), the existing
`value` must not be nullified. This protects against a partial update bug.

**Preconditions:**
- Override: `fieldPath = "build.javaVersion"`, `versionRange = "[1.0, 2.0)"`, `value = "21"`

**Acceptance criteria:**
1. `PATCH` with `{"versionRange": "[1.0, 3.0)"}` (without `value`) returns 200
2. Override has `value = "21"` (preserved)
3. `versionRange = "[1.0, 3.0)"` (updated)

**Test method:** —

---

### SYS-017: GET /meta/owners — unique owners

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Endpoint `GET /rest/api/4/meta/owners` returns a list of unique
`componentOwner` values from all components. Used for autocomplete in UI.

**Preconditions:**
- Components exist with `componentOwner` = `"user1"`, `"user2"`, `"user1"` (duplicate)

**Acceptance criteria:**
1. HTTP status 200
2. Response is an array of strings
3. `"user1"` appears exactly once (deduplicated)
4. `"user2"` is present
5. Array is sorted alphabetically

**Test method:** —

---

### SYS-018: POST /admin/migrate-defaults — nested structure

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Endpoint `POST /rest/api/4/admin/migrate-defaults` parses `Defaults.groovy` and
saves the result into `registry_config` as a nested JSON structure,
accessible via `GET /rest/api/4/admin/component-defaults`.

**Preconditions:**
- `Defaults.groovy` contains nested settings for build, escrow, jira

**Acceptance criteria:**
1. `POST /rest/api/4/admin/migrate-defaults` returns 200
2. `GET /rest/api/4/admin/component-defaults` returns JSON with fields `build`, `escrow`, `jira`
3. `build.buildSystem` has a value (not null)
4. Repeated `POST` overwrites existing defaults (idempotent)

**Test method:** —

---

### SYS-019: Audit log on component UPDATE

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
When a component is updated via PATCH, a record is created in `audit_log`
with action = `UPDATE`, old_value, new_value, and change_diff.

**Preconditions:**
- Component exists
- Audit log is enabled

**Acceptance criteria:**
1. `PATCH /rest/api/4/components/{id}` changing `displayName` returns 200
2. `audit_log` table contains a new record with `entity_type = "component"`, `action = "UPDATE"`
3. `old_value` contains the previous `displayName`
4. `new_value` contains the new value
5. `changed_by` contains the username

**Test method:** —

---

### SYS-020: Filtering by system, archived, search

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
Endpoint `GET /rest/api/4/components` supports query parameter filtering:
`system`, `archived`, `search` (by name/displayName).

**Preconditions:**
- Components with different `system` values, some with `archived = true`
- Component with `name = "TestComp"` and `displayName = "Test Component"`

**Acceptance criteria:**
1. `GET /rest/api/4/components?system=CLASSIC` returns only components with `system = "CLASSIC"`
2. `GET /rest/api/4/components?archived=true` returns only archived components
3. `GET /rest/api/4/components?search=TestComp` finds the component by name
4. `GET /rest/api/4/components?search=Test+Component` finds by displayName
5. Filters can be combined: `?system=CLASSIC&archived=false&search=Test`

**Test method:** —

---

### SYS-021: UI: Component list without JS errors

**Priority:** High
**Test layer:** e2e-test
**Status:** ❌ Not tested

**Description:**
The component list page (`/ui/components`) loads and displays a component table
without JavaScript errors in the browser console.

**Preconditions:**
- Server is running on `localhost:4567`
- Database contains migrated components

**Acceptance criteria:**
1. `GET /ui/components` returns 200 (HTML)
2. Page renders a table with > 0 rows
3. Browser console contains no JS errors (red)
4. Pagination works: clicking "Next" loads the next page

**Test method:** —

---

### SYS-022: UI: Component detail with tabs

**Priority:** High
**Test layer:** e2e-test
**Status:** ❌ Not tested

**Description:**
The component detail page (`/ui/components/{id}`) displays tabs:
General, Build, VCS, Distribution, Jira, Escrow.

**Preconditions:**
- Component exists and has populated configurations

**Acceptance criteria:**
1. Page loads without errors
2. All 6 tabs are visible: General, Build, VCS, Distribution, Jira, Escrow
3. Clicking each tab shows corresponding content
4. Data matches the API response from `GET /rest/api/4/components/{id}`

**Test method:** —

---

### SYS-023: UI: Navigation between pages

**Priority:** High
**Test layer:** e2e-test
**Status:** ❌ Not tested

**Description:**
React Router navigation works correctly: list → detail → back,
without full page reload.

**Preconditions:**
- Application is loaded

**Acceptance criteria:**
1. Clicking a component in the list navigates to `/ui/components/{id}`
2. "Back" button returns to `/ui/components`
3. Navigation happens without full page reload (SPA)
4. Direct URL navigation to `/ui/components/{id}` loads the component

**Test method:** —

---

### SYS-024: UI: Editable tabs have Save button

**Priority:** Medium
**Test layer:** e2e-test
**Status:** ❌ Not tested

**Description:**
Tabs with editable fields (General, Build, VCS, Distribution, Jira, Escrow)
have a Save button that sends a PATCH request.

**Preconditions:**
- User is on the component detail page

**Acceptance criteria:**
1. Each tab with editable fields shows a "Save" button
2. "Save" button is disabled when there are no changes
3. When a field is modified, the button becomes enabled
4. Clicking "Save" sends a PATCH request and updates the data
5. After successful save, the button is disabled again

**Test method:** —

---

### SYS-025: DatabaseComponentRegistryResolver applies field overrides

**Priority:** High
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
`DatabaseComponentRegistryResolver`, when querying a component for a specific version,
applies field overrides from the `field_overrides` table, replacing component default
values with override values when the version falls within the range.

**Preconditions:**
- Component has `build.buildSystem = "MAVEN"` (default)
- Override: `fieldPath = "build.buildSystem"`, `versionRange = "[1.0, 2.0)"`, `value = "GRADLE"`

**Acceptance criteria:**
1. `getComponent("comp", "1.5")` returns `build.buildSystem = "GRADLE"` (override applied)
2. `getComponent("comp", "2.5")` returns `build.buildSystem = "MAVEN"` (outside range, default)
3. `getComponent("comp", "1.0")` returns `"GRADLE"` (inclusive boundary)
4. `getComponent("comp", "2.0")` returns `"MAVEN"` (exclusive boundary)

**Test method:** —

---

### SYS-026: Flyway-managed PostgreSQL schema passes Hibernate validate

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Starting the server against a PostgreSQL database where Flyway has applied all
migrations (V1–VN) under `spring.jpa.hibernate.ddl-auto=validate` must succeed.
This guards against silent drift between Flyway DDL and the DDL Hibernate would
derive from the entity mapping — e.g. when a column's `@Column(columnDefinition = ...)`
is removed or loosened and the dialect-resolved default no longer matches the
Flyway-created column type (PR #148 removed `columnDefinition = "text[]"` from
`ComponentEntity.system`; this requirement pins the assumption that the default
Hibernate type for `@JdbcTypeCode(SqlTypes.ARRAY) Array<String>` under PostgreSQL
dialect resolves to `text[]` — or fails loudly if it does not).

**Preconditions:**
- PostgreSQL 16 instance (testcontainer) with Flyway-applied migrations V1→VN.
- Spring profile enables JPA with `ddl-auto=validate` and `dialect=PostgreSQLDialect`.

**Acceptance criteria:**
1. Spring context starts successfully with Flyway-applied schema + `ddl-auto=validate`.
2. Every `@Entity` whose table is present in the Flyway schema passes Hibernate's
   schema validation (`SchemaManagementException` is NOT thrown during startup).
3. If a mapping/column mismatch is introduced (e.g. removing `columnDefinition`
   on `system` causes Hibernate to infer `varchar[]` instead of `text[]`), the
   test fails with a clear error naming the offending column.

**Test method:** `FlywayValidatePostgresStartupTest.SYS-026 Flyway-managed PostgreSQL schema passes Hibernate validate`

---

### SYS-027: ft-db profile supports writes against jsonb columns

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
Under the `ft-db` profile (H2 in-memory, PostgreSQL compatibility mode, `ddl-auto=create-drop`,
Flyway disabled), write operations against entity columns declared with
`columnDefinition = "jsonb"` succeed and round-trip. Entities in scope include
`ComponentEntity.metadata`, `BuildConfigurationEntity.metadata`,
`FieldOverrideEntity.value`, `JiraComponentConfigEntity.metadata`,
`EscrowConfigurationEntity.metadata`, `DistributionEntity.metadata`,
`ComponentVersionEntity.metadata`, `RegistryConfigEntity.value`, and
`AuditLogEntity.oldValue/newValue/changeDiff`. Guards against DDL failures
(unknown type `jsonb` under H2) or Hibernate type-mapping drift on the H2 dialect.

**Preconditions:**
- Spring profiles `common,ft-db` are active.
- Auto-migrate has populated the DB — `GET /rest/api/4/components` returns at least one component.

**Acceptance criteria:**
1. `PATCH /rest/api/4/components/{id}` with a `buildConfiguration.metadata` map returns 2xx.
2. Follow-up `GET /rest/api/4/components/{id}` reflects the written `buildConfiguration.metadata`
   values (non-string scalars and nested structures round-trip).
3. `POST /rest/api/4/components/{id}/field-overrides` with a non-trivial `value` (nested map)
   returns 2xx.
4. Follow-up `GET /rest/api/4/components/{id}/field-overrides` contains the created override
   with the exact `value` that was written.

**Test method:** `FtDbProfileWriteTest` (`SYS-027 PATCH buildConfiguration metadata round-trips`,
`SYS-027 POST field override value round-trips`)

---

### SYS-028: v4 API supports component rename

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
Under the legacy git-resolver flow, downstream tooling (e.g. Releng's Jira workflow)
renamed components by editing Groovy DSL files in the mounted `/components-registry`
volume and relying on CRS to re-read DSL on the next request. Under `ft-db` (and, by
extension, any DB-backed deployment) CRS loads DSL only once at startup and serves
from the DB thereafter — DSL edits during the run are ignored by design. Without a
rename endpoint, downstream rename workflows silently break once CRS moves to DB.

**Description:**
Renaming is a partial field update, so per Octopus REST API Guidelines § 1.3 it is
exposed as `PATCH /rest/api/4/components/{id}` with body
`{"version": N, "name": "<new>"}`. The operation is transactional; related aggregates
(`component_versions`, `component_artifact_ids`, `vcs_settings`, `jira_component_configs`,
`escrow_configurations`, `distributions`, `distribution_artifacts`, `build_configurations`,
`field_overrides`) all key by `component_id` (UUID) and therefore cascade automatically
through Hibernate. The only name-keyed row — `component_source` — is rewritten
atomically by `ComponentSourceRegistry.renameComponent`.

For callers that only know the component by name (releng, DMS, ORMS conventions) the
existing `GET /rest/api/4/components/{idOrName}` accepts either a UUID or a name and
dispatches accordingly — matching peer-service and CRS v1–v3 convention.

**Preconditions:**
- Component with the given `id` exists.
- Request `version` matches the entity's current `@Version` (optimistic lock).
- `name` (when present) is non-blank and does not collide with another component.

**Acceptance criteria:**
1. Happy path: `PATCH /rest/api/4/components/{id} {"version":N,"name":"NEW"}` returns 200.
   Subsequent `GET /rest/api/4/components/NEW` returns the component; `GET /rest/api/4/components/OLD`
   returns 404.
2. Cascade: all related rows continue to resolve from either id or new name.
3. Conflict: PATCH with `name` equal to an existing component's name returns 409.
4. Missing id: PATCH on a non-existing UUID returns 404.
5. Validation: blank / whitespace `name` returns 400.
6. Audit: when `name` actually changes, writes one `audit_log` entry with
   `action = "RENAME"`, `oldValue.name = OLD`, `newValue.name = NEW`, current actor,
   and timestamp. Other field updates in the same PATCH are folded into the same entry.
   If `name` does not change, action stays `"UPDATE"` as before.
7. Concurrency: PATCH with a stale `version` rejects the change via optimistic-lock
   semantics.
8. Combined update: a single PATCH may rename AND update other fields in one call;
   both are applied transactionally and a single audit event is emitted.

**Test method:** `ComponentRenameTest` — 8 cases covering each acceptance criterion
against an ft-db H2 fixture. Each test creates its own throwaway component via
`POST /rest/api/4/components` to stay isolated; the class uses
`@DirtiesContext(AFTER_CLASS)` so those throwaway rows do not bleed into
`FtDbProfileTest` (which asserts on DSL-backed v1/v3 response shape).

**Out of scope for this requirement:**
- Releng's Jira workflow switching from DSL-edit to the new API — that is a separate
  change in the downstream repo, tracked outside CRS.
- Renaming a git-sourced component — only DB-sourced components are in scope; a
  git-sourced rename would still need migration-to-DB first.

### SYS-029: Renamed-away name no longer resolvable via v1/v2/v3 under ft-db

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
After a successful `PATCH /rest/api/4/components/{id}` rename under the ft-db
profile, downstream FT (Releng JIRA-Releng-Plugin build 8.5138) still saw the
OLD name when it called CRS v1 to validate the rename. The plugin's internal
post-rename check (`ComponentManagementRestService.renameComponent`) then
refused to update its own DB with 400 "The component X still exists in CR".

The root cause was in `ComponentRoutingResolver`. It routes a lookup to the
DB or Git resolver via `sourceRegistry.isDbComponent(name)`. After rename,
the `component_source` row is rewritten atomically from the old name to the
new name — so the OLD name no longer has a row. `isDbComponent(OLD)` then
returned false, the request was routed to the Git resolver, and the Git
resolver's in-memory `configuration` — loaded from the mounted DSL once at
startup and never updated during the run — still contained the pre-rename
entry. The resolver happily returned that stale DSL entity.

Under ft-db (where auto-migrate has moved everything to the DB), the Git
resolver should never be consulted for post-rename or post-delete lookups.

**Description:**
A `components-registry.default-source` config property, default `"git"`,
sets the effective source for names without a `component_source` row. The
`ft-db` profile sets it to `"db"` so:

- All unknown / renamed-away / deleted names route to the DB resolver, which
  correctly returns `null` (→ 404) instead of falling back to the DSL.
- Mixed-source deployments (git-sourced DSL, incrementally migrating to DB)
  leave the default as `"git"` and behavior is unchanged.

The migration code in `ImportServiceImpl` must continue to treat "no row"
as "not yet migrated" (otherwise auto-migrate would skip everything under
ft-db). `ComponentSourceRegistryImpl.isDbComponent` and `isGitComponent`
therefore check the row literally, while `getSource` applies the
`default-source` fallback for routing.

**Acceptance criteria:**
1. After `PATCH /rest/api/4/components/{id} {"name":"NEW"}` on a DSL-migrated
   component under ft-db, `GET /rest/api/1/components/OLD` returns 404.
2. Under the ft-db profile with `default-source: db`, auto-migrate on a cold
   boot still migrates every DSL component to the DB (not a no-op).
3. Under the default profile (`default-source` unset → "git"), routing and
   migration behavior is byte-for-byte identical to before.

**Test method:** `GhostComponentAfterRenameTest.SYS-029: v1 GET must return
404 for a name that was renamed away under ft-db`. Regression coverage for
auto-migrate comes from `FtDbProfileTest.all components are in DB after
startup with ft-db profile`.

**Out of scope for this requirement:**
- Propagating renames through downstream caches that CRS does not own (Releng
  `ComponentRegistryService`, DMS client cache) — callers must invalidate on
  their side. CRS's contribution is to expose a consistent view across API
  versions.

### SYS-030: DistributionEntity round-trips groupId-only GAV without `:null` suffix

**Priority:** High
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
Releng Maven-CRM-Plugin IT build 8.5138 surfaced
`SetDistributionParametersTest.testDefaultParameters` and `testExplicitExternal`
failing with assertion `'...ee:null' must contain '...ee'`. The maven-crm-plugin
emits `##teamcity[setParameter name='DISTRIBUTION_ARTIFACTS_COORDINATES'
value='org.example.teamcity.ee:null']` when the DSL fixture specifies
`GAV = "org.example.teamcity.ee"` (groupId-only, no `:artifact`).

The root cause is in `DistributionEntity.toDistribution()` (EntityMappers.kt).
The read-back builds the GAV as
`"${groupPattern}:${artifactPattern}"` plus optional `:extension` / `:classifier`.
Kotlin string templates render `null` as the literal string `"null"`, so a
groupId-only entity — where `artifactPattern`, `extension`, and `classifier`
are all null — came back as `"org.example.teamcity.ee:null"`.

Under the git-sourced profile the DSL never passes through DB mappers, so the
bug was invisible. Under `ft-db` auto-migrate writes to the DB and v1 reads
come back via the DB path — surfacing the defect the moment a downstream
uses v1 distribution lookups.

**Description:**
`DistributionEntity.toDistribution()` must produce the exact GAV string that
was stored, for every shape the write-side mapper supports:
- `groupId` alone
- `group:artifact`
- `group:artifact:extension`
- `group:artifact:extension:classifier`
- multi-GAV strings stored in `name`

**Acceptance criteria:**
1. All five shapes round-trip byte-for-byte.
2. No new string contains the literal `":null"`.

**Test method:** `DistributionEntityMapperTest` — one test per shape.

**Out of scope:**
- Migrating historical rows that were ingested with `:null` baked into `name`
  (none observed; write-side doesn't produce this for non-raw storage).

### SYS-031: DistributionEntity round-trips multi-image docker coordinates verbatim

**Priority:** High
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
The DSL allows multiple docker images in a single `docker` declaration via a
comma-separated list like `"image:tag1,image:tag2"`. The original
`toDistributionEntity` mapper split the value on `:` and kept only
`getOrNull(0)` and `getOrNull(1)` — for the multi-image string above the
first `:tag1,image` ended up in `tag`, and everything after the second `:`
was dropped on write. Read-back therefore returned `"image:tag1,image"`.

Surfaced downstream in a Maven-CRM-Plugin IT
(`SetDistributionParametersTest.testDefaultParameters` / `testExplicitExternal`)
where `DISTRIBUTION_ARTIFACTS_COORDINATES_DOCKER` came back one suffix short
of the DSL value after the CRS container loaded its DSL through ft-db.

The GAV side already had the same problem (SYS-030 / multi-GAV raw-string
storage); docker simply didn't inherit the treatment.

**Description:**
`toDistributionEntity` stores a multi-image docker string (contains `,`) as a
single artifact row with `name = <raw string>` and `tag = null`, symmetric
with multi-GAV. `toDistribution` already returns `name` verbatim when `tag`
is null — no read-side change needed.

**Acceptance criteria:**
1. Single-image `image:tag` round-trips verbatim.
2. Multi-image `img1:t1,img2:t2` round-trips verbatim.
3. Existing single-image behavior is unchanged.

**Test method:** `DistributionEntityMapperTest.singleDocker_roundtrip` and
`.multiDocker_roundtrip`. Both exercise the write-side (`toDistributionEntity`)
and the read-side (`toDistribution`). Visibility of `toDistributionEntity` was
relaxed from `private` to `internal` for these tests.

**Out of scope:**
- Validating the structural contents of each image entry (name format, tag
  constraints). The mapper only guarantees verbatim round-trip; callers
  parse individual entries.

### SYS-032: ComponentSourceRegistry reads reflect cross-pod DB changes on every call

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
CRS is deployed behind a horizontally-scaled pod set against one shared
database. The original `ComponentSourceRegistryImpl` wrapped every
`getSource(name)` read in a per-JVM Caffeine cache with a 5-minute
write-expiry. A `component_source` rewrite (rename / delete / insert) on
pod A was therefore invisible to pod B's `ComponentRoutingResolver.resolverFor`
— which calls `getSource` — until that pod's cache entry expired. For
those five minutes pod B continued to route the old name through the DB
resolver (or the git resolver, depending on which side cached first),
re-introducing the exact ghost SYS-029 eliminated on a single-JVM setup.

`isDbComponent` / `isGitComponent` were already on a cache-free path since
the SYS-029 fix (literal `findById` row check), so migration bookkeeping
stayed correct. The hot cached path was routing only.

**Description:**
Drop the Caffeine cache entirely from `ComponentSourceRegistryImpl`. The
`component_source.name` column is a primary key with a unique index, so
`findById` is a sub-millisecond PK seek on H2 and an indexed disk read on
Postgres — acceptable per-request overhead for a metadata service, and
the only correct baseline for multi-pod deployments.

If this hot path later becomes a measured bottleneck, the right next step
is a Hibernate L2 cache with cluster-aware invalidation (e.g.
Infinispan / Hazelcast), not a per-JVM Caffeine cache.

**Acceptance criteria:**
1. `getSource(name)` reflects a direct repository delete on the very next
   call (no stale routing).
2. `getSource(name)` reflects a direct repository insert on the very next
   call.
3. `getSource(oldName)` / `getSource(newName)` reflect a direct repository
   rename on the very next call.
4. Existing SYS-029 / SYS-030 / SYS-031 coverage continues to pass.

**Test method:** `ComponentSourceRegistryMultiPodTest` — three scenarios
(`_reflectsExternalDelete`, `_reflectsExternalInsert`,
`_reflectsExternalRename`) that simulate a second pod by writing directly
through the shared `ComponentSourceRepository`.

**Out of scope:**
- Designing the cluster-aware L2 cache for later performance work.
- In-flight writes and isolation semantics (covered by ordinary JPA
  transaction / optimistic-lock mechanics).

---

### SYS-033: GET /rest/api/4/info returns build name and version, anonymous access

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
The portal footer needs to display the running CRS service version next to the
portal version (DMS-style "Components Registry by F1 team (portal X · service
Y)"). The portal proxies `/rest/**` to CRS, but the SecurityConfig on both
sides currently authenticates everything under `/rest/api/4/**`. To keep the
footer working before the user has logged in (and to avoid 401-noise in logs),
expose `/rest/api/4/info` anonymously on CRS, sourcing the values from
Spring Boot's `BuildProperties` bean (`springBoot { buildInfo() }` is already
enabled in the server module).

**Preconditions:**
- `META-INF/build-info.properties` is generated at build time (already wired
  via `springBoot.buildInfo()`).

**Acceptance criteria:**
1. `GET /rest/api/4/info` without an `Authorization` header returns HTTP 200.
2. Response body is `application/json` containing fields `name` (string) and
   `version` (string).
3. The returned `version` matches `BuildProperties.getVersion()`.
4. The endpoint is reachable through `WebSecurityConfig` without a valid JWT
   (i.e. the path is added to `permitAll()` and not just to a slice test).

**Test method:** `InfoControllerV4Test.SYS-033 anonymous GET info returns build name and version` (full `@SpringBootTest` MockMvc — exercises the real `WebSecurityConfig` chain).

**Out of scope:**
- Caching or rate-limiting the endpoint (single read per page load is fine).
- Returning git SHA or build timestamp — only `name`/`version` for the footer.

---

### SYS-034: GET /auth/me returns current user, authenticated

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not tested

**Description:**
The Portal needs to render the signed-in user (username, roles) in the header/admin gates. CRS exposes `GET /auth/me` which delegates to `octopus-cloud-commons` `SecurityService.getCurrentUser()` and returns a `User { username, roles, groups }`. Path is **outside** `/rest/api/4` — it's at the top-level `/auth` so the Portal gateway can proxy it on the `/auth/**` route.

**Preconditions:**
- A valid JWT for a user with at least `ROLE_REGISTRY_VIEWER` (or any authenticated role).

**Acceptance criteria:**
1. `GET /auth/me` without `Authorization` header → HTTP 401.
2. `GET /auth/me` with valid JWT → HTTP 200, `application/json` body `{ "username": ..., "roles": [...], "groups": [...] }`.
3. Returned `username` equals the JWT `preferred_username` claim (or whatever `SecurityService` resolves it from for the current cloud-commons version).
4. Returned `roles` reflect Keycloak realm roles after `UserInfoGrantedAuthoritiesConverter` mapping.

**Test method:** —

**Out of scope:**
- Modifying user data (this is read-only).
- Custom claims beyond what cloud-commons `User` exposes.

---

### SYS-035: GET /components?owner=&lt;username&gt; filters by componentOwner exact match

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
The Portal `ComponentFilters` UI offers an owner dropdown populated from `/components/meta/owners`. Without a server-side `owner` filter the SPA would have to download the entire components list (~900 rows) on every owner-filter change, defeating server-side pagination. This requirement adds an `owner` query parameter to `GET /rest/api/4/components` that filters rows whose `componentOwner` equals the supplied value (exact, case-sensitive — values come from the same `/meta/owners` source the autocomplete uses).

**Preconditions:**
- DB contains components with various `componentOwner` values (or none).
- Caller authenticated with `ACCESS_COMPONENTS` (anonymous is acceptable for reads on v4).

**Acceptance criteria:**
1. `GET /rest/api/4/components?owner=alice` returns HTTP 200 and the `content` array contains only components whose `componentOwner == "alice"`.
2. `GET /rest/api/4/components?owner=<unknown>` returns HTTP 200 with an empty `content` array and `totalElements == 0`.
3. `GET /rest/api/4/components` without the `owner` param returns HTTP 200 with the full list (the parameter is optional).
4. `owner` combines with the other supported filters (`productType`, `archived`, `search`) via AND. (`system` is currently rejected with HTTP 400 — see `ComponentManagementServiceImpl.buildSpecification` — and is therefore not part of this combination.)

**Test method:** `ListComponentsOwnerFilterTest` — four test methods covering criteria 1, 2, 3, 4 against the live `WebSecurityConfig` chain on the `ft-db` profile.

**Out of scope:**
- Multi-owner / OR semantics (a single value per call; combine with other filters or paginate).
- Substring or fuzzy match on owner (the autocomplete passes the exact value; substring would defeat the DB index).
- Owner aliases / SSO username mapping — accept whatever the DB has.

---

### SYS-036: GET /audit/recent accepts filter params

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Description:**
The Portal `AuditLogPage` filter sidebar drives a server-side query — without filter params on `/audit/recent` the SPA would have to page through the full audit log to apply filters client-side, which doesn't scale beyond a few hundred rows. This requirement adds a set of independently-optional filter query params to `GET /rest/api/4/audit/recent`. Combinations are ANDed; an empty filter is equivalent to "all rows, newest first" (preserves the legacy unfiltered behaviour).

Supported filters:
| Param | Type | Notes |
|---|---|---|
| `entityType` | string | Currently only `Component` is emitted (capitalized — `cb.equal` is case-sensitive). `FieldOverride` and other entity types are reserved for future audit instrumentation; field-override CRUD does not publish `AuditEvent` yet. |
| `entityId` | string | usually a UUID; combine with `entityType` for entity-scoped history reachable in the same query as user/source filters |
| `changedBy` | string | username from `audit_log.changed_by`. For runtime API events the value is resolved by `CurrentUserResolver` (see TDD §6.4); for git-history backfill rows it is the git author signature. |
| `source` | string | Currently only `api` (default for runtime events) and `git-history` (backfill from `/admin/migrate-history`) are emitted. Other values are reserved for future writers. |
| `action` | string | `CREATE` \| `UPDATE` \| `DELETE` \| `RENAME` \| `ARCHIVE` \| … |
| `from`, `to` | ISO-8601 instant | half-open `[from, to)` over `audit_log.changed_at`; either or both optional |

**Preconditions:**
- Caller authenticated with `ACCESS_AUDIT`.
- DB contains audit rows produced by either runtime API events (`source = 'api'`) or backfill from `/admin/migrate-history` (`source = 'git-history'`).

**Acceptance criteria:**
1. `GET /audit/recent?entityType=Component` returns only rows with `entityType == "Component"`.
2. `GET /audit/recent?entityType=Component&entityId=<uuid>` returns only rows for that single component (CREATE + any UPDATE/RENAME/DELETE).
3. `GET /audit/recent?changedBy=alice` returns only rows with `changedBy == "alice"`. Depends on `CurrentUserResolver` populating `audit_log.changed_by` (TDD §6.4) — without that wiring, runtime API rows would have `changed_by = null` and the filter would always be empty.
4. `GET /audit/recent?changedBy=<unknown>` returns HTTP 200 with empty `content` and `totalElements == 0`.
5. `GET /audit/recent?source=api` returns only rows with `source == "api"`; rows with `source == "git-history"` are excluded.
6. `GET /audit/recent` without any filter param returns the full audit log newest-first (legacy contract preserved).
7. Default sort when caller has not supplied `sort=`: `changedAt DESC`. Caller-supplied `sort=` overrides.

**Test method:** `AuditLogFilterTest` — six test methods covering criteria 1–6 against live `AuditService.getRecentChanges` Specification on the `ft-db` profile.

**Out of scope:**
- Free-text search inside `oldValue` / `newValue` / `changeDiff` JSON.
- OR-combining values inside a single param (e.g. `source=api,git-history` — pass them as separate calls or use a different endpoint when needed).
- Pagination metadata changes — Spring Data `Pageable` + `Page<>` shape unchanged.

---

### SYS-037: v4 CRUD API for dependency mappings

**Priority:** Medium
**Test layer:** integration-test
**Status:** ❌ Not implemented

**Motivation:**
`dependency_mapping.properties` in the Git DSL defined alias→component mappings consumed by downstream tools via `GET /rest/api/2/dependency-mapping`. These mappings live in the `dependency_mappings` table (`DependencyMappingEntity`: `alias` PK, `componentName`), populated during `/admin/migrate-history`. Currently there is no v4 management API — edits require direct DB access or re-running the history backfill. UI for editing these mappings is not planned (they change rarely); the API alone is sufficient to remove the Git-DSL dependency for this data.

**Description:**
Add four endpoints under `/rest/api/4/dependency-mappings`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/rest/api/4/dependency-mappings` | Return all mappings as a list of `{alias, componentName}`. No pagination needed (expected < 1000 entries). |
| `POST` | `/rest/api/4/dependency-mappings` | Create a new mapping. Body: `{alias, componentName}`. Returns 201 with the created resource. 409 if `alias` already exists. |
| `PUT` | `/rest/api/4/dependency-mappings/{alias}` | Replace `componentName` for an existing alias. Returns 200. 404 if alias not found. |
| `DELETE` | `/rest/api/4/dependency-mappings/{alias}` | Delete a mapping. Returns 204. 404 if alias not found. |

Auth: `@PreAuthorize("@permissionEvaluator.canImport()")` (same gate as other admin data-management endpoints — `IMPORT_DATA` permission, `ROLE_REGISTRY_ADMIN` in the default role map).

The existing v2 read endpoint (`GET /rest/api/2/dependency-mapping`) remains unchanged — it reads from the same `dependency_mappings` table.

**Preconditions:**
- Caller has `ROLE_REGISTRY_ADMIN` (or a role that grants `IMPORT_DATA`).
- `dependency_mappings` table exists (V1 schema).

**Acceptance criteria:**
1. `GET /rest/api/4/dependency-mappings` (authenticated) returns HTTP 200 and an array of `{alias, componentName}` objects. An empty table returns `[]`.
2. `POST /rest/api/4/dependency-mappings` with `{"alias": "foo", "componentName": "bar"}` returns 201 and `GET` lists the new entry.
3. `POST` with a duplicate `alias` returns 409 Conflict.
4. `PUT /rest/api/4/dependency-mappings/foo` with `{"componentName": "baz"}` returns 200 and updates the mapping.
5. `PUT` for a non-existent alias returns 404.
6. `DELETE /rest/api/4/dependency-mappings/foo` returns 204 and subsequent `GET` no longer lists the entry.
7. `DELETE` for a non-existent alias returns 404.
8. All write endpoints require auth; `GET` without a valid JWT returns 401 (consistent with other v4 non-info endpoints).
9. The existing `GET /rest/api/2/dependency-mapping` response is unaffected by v4 CRUD operations (reads same table).

**Test method:** —

**Out of scope:**
- Bulk import/replace-all (single-entry operations are sufficient; bulk is handled by `/admin/migrate-history`).
- Portal UI — not planned; the v2 read endpoint and direct API calls cover the use cases.
- Pagination — the mapping set is small enough for a flat list response.
