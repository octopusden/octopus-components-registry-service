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
