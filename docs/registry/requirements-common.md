# Common System Requirements

## Status

**Draft** | Date: 2026-03-16

---

## Summary Table

| ID      | Title | Priority | Layer | Status |
|---------|-------|----------|-------|--------|
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
| SYS-038 | Domain-named meta endpoints for free-form aspect option lists (buildSystem / repositoryType / generation) | Medium | integration-test | ✅ Tested |
| SYS-040 | GET /components?labels=A,B filters by component_labels junction (AND across selected labels); GET /components/meta/labels returns sorted distinct codes in use | High | integration-test | ✅ Tested |
| SYS-041 | GET /components?buildSystem=GRADLE,MAVEN accepts CSV multi-value with OR semantics across the BASE buildSystem column | High | integration-test | ✅ Tested |
| SYS-042 | GET /components?system=A,B accepts CSV multi-value with OR semantics across the `component_systems` M:N junction (a component may belong to several systems); GET /components/meta/systems returns sorted distinct codes in use | High | integration-test | ✅ Tested |
| SYS-043 | GET /components?owner=alice,bob accepts CSV multi-value with OR semantics over the scalar componentOwner column | High | integration-test | ✅ Tested |
| SYS-044 | releaseManager / securityChampion are ordered multi-value (`string[]`) in v4 with keep-first dedupe; legacy v1/v2/v3 keep the comma-joined `String`; componentOwner stays single-value and is removed from the global component-defaults surface | High | unit + integration-test | ✅ Tested |
| SYS-045 | GET /components?distributionExplicit= / ?distributionExternal= activate the two scalar distribution boolean filters (mirror `solution`; `=false` excludes NULL rows) | Medium | integration-test | ✅ Tested |
| SYS-046 | clientCode / jiraProjectKey / parentComponentName / groupKey become multi-value exact-IN filters (CSV / repeatable); 4 companion in-use meta endpoints (/meta/client-codes, /meta/jira-project-keys, /meta/parent-component-names, /meta/group-keys) | High | integration-test | ✅ Tested |
| SYS-047 | git-only no-DB boot mode: the `no-db` profile excludes the JDBC/JPA/Flyway auto-configs and gates every DB-coupled bean off (`@ConditionalOnDatabaseEnabled`), so the service boots with no database and serves v1/v2/v3 from the Git resolver — the compat git-mode stand (id18) needs no Postgres | High | unit / context-load test | ✅ Tested |
| SYS-048 | A no-op write (component or field-override save whose old/new snapshots carry no field-level diff) writes no audit row; CREATE/DELETE keep theirs | High | unit-test | ✅ Tested |
| SYS-049 | Git-history backfill records a component's first appearance with action `MIGRATED` (not `CREATE`); both audit read endpoints hide `MIGRATED` by default and opt back in via `includeMigrated=true`. `/audit/recent` additionally honours an explicit `action=MIGRATED` filter (the entity-history endpoint takes only `includeMigrated`) | High | unit + integration-test | ✅ Tested |
| SYS-050 | Field-override (version-range) create/update/delete each publish a Component `UPDATE` audit event with an attribute-keyed diff | High | integration-test | ✅ Tested |
| SYS-051 | TeamCity sync (automated `changedBy=system` reconciliation) does NOT write an audit row — the re-link is traced via an INFO log instead | Medium | unit-test | ✅ Tested |
| SYS-052 | An `employee-service.url` whose placeholder does not resolve in the current environment is treated as "not configured": the client bean is not registered and context refresh succeeds (fail-open), instead of failing application boot | High | unit-test | ✅ Tested |
| SYS-053 | Component audit snapshots include section fields (BASE-configuration build/escrow/jira scalars, versionRange, section + per-component child collections), so a section-only PATCH writes an UPDATE audit row with a field-level diff instead of being dropped by the SYS-048 no-op guard; collection snapshots are content-only (no row ids) so an identical re-save stays a no-op | High | integration-test | ✅ Tested |
| SYS-054 | Audit read endpoints expose a server-resolved `componentKey` on each Component row: resolved from the `entityId` UUID to the component's current key, batched per page; falls back to the snapshot `name` (CRUD) / `moduleName` (MIGRATED) for components that no longer exist; `null` for non-Component rows or when unresolvable. Covers field-override rows whose value snapshot carries no name | High | integration-test | ✅ Tested |
| SYS-055 | Anonymous `GET /rest/api/4/migration-status` reports whether a migration/resync job is RUNNING ({running, kind}) so a tokenless caller (the portal validation sweep) can skip while the legacy resolver may serve not-yet-migrated data | Medium | integration-test | ✅ Tested |
| SYS-056 | `GET /components?releaseManager=<u>` / `?securityChampion=<u>` filter the list to components on which the user is a release manager / security champion (JOIN through the ordered child tables, OR across CSV / repeatable values, distinct); the list summary additionally emits `releaseManagers` / `securityChampions` username arrays (batched, emitted-empty-not-null) | High | integration-test | ✅ Tested |
| SYS-057 | `GET /rest/api/4/health/statistics` returns registry-wide counts (`totalComponents`, `activeComponents`) and people-dimension breakdowns (`componentsByOwner`, `componentsByReleaseManager`, `componentsBySecurityChampion`) computed via SQL GROUP BY (never by loading all components into memory); ACCESS_COMPONENTS-gated; counts + people only (problem/validation aggregation is portal-owned) | High | integration-test | ✅ Tested |
| SYS-058 | Artifact-ID ownership is modelled explicitly as a per-component LIST of `(group-list, mode ∈ {EXPLICIT, ALL_EXCEPT_CLAIMED, ALL}, version range)` mappings (`component_artifact_mappings` + `_tokens`), replacing the opaque regex `component_artifact_ids`. Cross-component uniqueness is decided deterministically from the stored modes (restores legacy validator #24/#25), enforced on v4 create/update (409); per-range overrides REPLACE the base for their range; v1–v3 wire renders the primary mapping; migration classifies DSL patterns into modes strictly (no escape hatch) | High | unit + integration-test | ✅ Tested |
| SYS-059 | `POST /rest/api/4/versions/preview` renders a `DetailedComponentVersion` from ad-hoc Jira formats (base + per-range overrides) and an input version, with no persistence and no component lookup — reusing the persisted-path render seam (including `normalizeVersion` canonicalization) so output matches `detailed-version` for the same effective config. Range is resolved server-side; `line`/`build` mirror `minor`/`release` and `minor`/`release` default to `$major`/`$major.$minor` when blank; hotfix coordinate gated on caller-supplied `hotfixEnabled` (VCS-derived), not format presence; custom `versionPrefix`/`versionFormat` render the wrapped `jiraVersion`; padding is template-driven (no `buildSystem`); blank/non-numeric version or malformed range → 400, a version matching no format → 404; authenticated-only | High | unit + integration-test | ✅ Tested |
| SYS-060 | An append-only `service_event` journal persists operational events — CRS redeploys (STARTUP + build version), and every components-migration / history-migration / TeamCity-resync run (RUNNING→COMPLETED/FAILED, one row per run) — which previously lived only in an in-memory slot + logs and were lost on restart. **Any job failure (including executor-rejected submission) writes a FAILED row with the error**; a run whose pod dies mid-flight is reconciled to FAILED("interrupted by restart") on next startup (single-pod). Writes are best-effort (`REQUIRES_NEW` + swallow) so journaling never rolls back or crashes the observed job. `GET /rest/api/4/admin/service-events` returns the paginated journal (filter by type/source/status/time), IMPORT_DATA-gated; a scheduled daily prune enforces retention | High | unit + integration-test | ✅ Tested |
| SYS-061 | `POST /rest/api/4/admin/service-events` ingests portal-sourced events (portal redeploys, validation-sweep runs) into the shared journal, so the Admin "Events" tab shows both services on one timeline. Authenticated by a shared-secret `X-Service-Event-Token` header (the portal BFF calls CRS tokenless), verified constant-time and **fail-closed** (blank/unset configured token rejects every call 403); method-scoped permitAll at the filter chain so the sibling GET read stays JWT+IMPORT_DATA gated; unknown eventType/status/source → 400 | High | integration-test | ✅ Tested |
| SYS-064 | `component-validation` module: `ATTACHED_TO_BUILD_TEMPLATE` is OK when exactly one build configuration is attached to a build template, WARNING when more than one is (ambiguous), WARNING when none is (always applicable) | High | unit-test | ✅ Tested |
| SYS-065 | `component-validation` module: `OVERRIDES_DEFAULT_BUILD_STEP` is NOT_APPLICABLE with no attached configuration, WARNING if any attached configuration's default build step is overridden, OK if all are inherited | High | unit-test | ✅ Tested |
| SYS-066 | `component-validation` module: `HAS_CUSTOM_BUILD_STEP` is WARNING when any uninherited build step across every configuration (attached to a template or not) resolves any tool version at all — Java or Maven, whichever it uses; OK otherwise | High | unit-test | ✅ Tested |
| SYS-067 | `component-validation` module: `USES_OLD_JAVA_VERSION` is WARNING if any resolved Java version is 1.8 (every uninherited step across every configuration, plus each attached configuration's default build step regardless of its own inherited flag), OK if versions resolved but none is 1.8, NOT_APPLICABLE if nothing Java was inspected; an unresolved version is ignored, not flagged (decision D7) | High | unit-test | ✅ Tested |
| SYS-068 | `component-validation` module: `ValidatorSuite.validate` isolates a throwing validator as a single `Status.ERROR` result instead of losing every other check's result (decision D6) | Medium | unit-test | ✅ Tested |
| SYS-069 | `component-validation` module: `JavaVersion.isEight` is true only for the exact `"1.8"` / `"8"` spellings; `JavaVersionParser` normalizes richer real-world values (`"1.8.0_392"`, `"JDK_ZULU_17_x64"`, `"/opt/java/openjdk-11"`, etc. — see TD-016) down to that major-version form before `isEight` is checked | Medium | unit-test | ✅ Tested |
| SYS-070 | `component-validation` module: `TeamCityValidators` (the suite) returns exactly the five TeamCity results for a given project, each with the status the individual checks would produce in isolation | High | unit-test | ✅ Tested |
| SYS-071 | `component-validation` module: `BuildStepToolVersionResolver` — given one `BuildStep`, dispatch by `StepType` (Maven/Gradle/command-line+in-container) to read the right parameter(s), recursively resolve `%param%` references via `ParameterReferenceResolver`, and derive `Set<ToolVersion>` via the per-tool `ValueVersionResolver`s (`JavaVersionResolver`, `MavenVersionResolver`) | High | unit-test | ✅ Tested |
| SYS-072 | `component-validation` module: `MULTIPLE_JAVA_VERSIONS` is WARNING when more than one distinct Java version is found across the same inspected steps as `USES_OLD_JAVA_VERSION`, OK for zero or one distinct version, NOT_APPLICABLE if nothing was inspectable | High | unit-test | ✅ Tested |
| SYS-073 | `component-validation` module: `MULTIPLE_MAVEN_VERSIONS` is WARNING when more than one distinct Maven version is found across the same inspected steps, OK for zero or one distinct version, NOT_APPLICABLE if nothing was inspectable | High | unit-test | ✅ Tested |
| SYS-074 | The component owner's manager (resolved via employee-service `getManager`) may edit the component and its field-overrides — a fourth, derived condition on `canEditComponent` alongside owner/RM/SC/admin. A directory failure or no-manager answer denies (fail-closed), never grants. `GET /{idOrName}/editors` enumerates the resolved manager (unlike the admin bypass, it is one concrete person per component); `getManager` is 2-minute cached per owner (resolved answers only) and its DB read runs in its own short-lived transaction, closed before the network call | High | unit + integration-test | ✅ Tested |

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
When a component is updated via PATCH **with an actual change**, a record is
created in `audit_log` with action = `UPDATE`, old_value, new_value, and
change_diff. A no-op PATCH (nothing changed) writes no row — see SYS-048.

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
- A valid JWT for a user with at least `ROLE_COMPONENTS_REGISTRY_VIEWER` (or any authenticated role).

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
| `entityType` | string | Currently only `Component` is emitted (capitalized — `cb.equal` is case-sensitive). `FieldOverride` and other entity types are reserved for future audit instrumentation; field-override CRUD is audited as a `Component` `UPDATE` (SYS-050), not under a `FieldOverride` entity type. |
| `entityId` | string | usually a UUID; combine with `entityType` for entity-scoped history reachable in the same query as user/source filters |
| `changedBy` | string | username from `audit_log.changed_by`. For runtime API events the value is resolved by `CurrentUserResolver` (see TDD §6.4); for git-history backfill rows it is the git author signature. |
| `source` | string | Currently only `api` (default for runtime events) and `git-history` (backfill from `/admin/migrate-history`) are emitted. Other values are reserved for future writers. |
| `action` | string | `CREATE` \| `UPDATE` \| `DELETE` \| `RENAME` \| `MIGRATED` (hidden by default — see SYS-049) |
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
`dependency_mapping.properties` in the Git DSL defined alias→component mappings consumed by downstream tools via `GET /rest/api/2/common/dependency-aliases`. These mappings live in the `dependency_mappings` table (`DependencyMappingEntity`: `alias` PK, `componentName`), populated during `/admin/migrate-history`. Currently there is no v4 management API — edits require direct DB access or re-running the history backfill. UI for editing these mappings is not planned (they change rarely); the API alone is sufficient to remove the Git-DSL dependency for this data.

**Description:**
Add four endpoints under `/rest/api/4/dependency-mappings`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/rest/api/4/dependency-mappings` | Return all mappings as a list of `{alias, componentName}`. No pagination needed (expected < 1000 entries). |
| `POST` | `/rest/api/4/dependency-mappings` | Create a new mapping. Body: `{alias, componentName}`. Returns 201 with the created resource. 409 if `alias` already exists. |
| `PUT` | `/rest/api/4/dependency-mappings/{alias}` | Replace `componentName` for an existing alias. Returns 200. 404 if alias not found. |
| `DELETE` | `/rest/api/4/dependency-mappings/{alias}` | Delete a mapping. Returns 204. 404 if alias not found. |

Auth: `@PreAuthorize("@permissionEvaluator.canImport()")` (same gate as other admin data-management endpoints — `IMPORT_DATA` permission, `ROLE_ADMIN` in the default role map).

The existing v2 read endpoint (`GET /rest/api/2/common/dependency-aliases`) remains unchanged — it reads from the same `dependency_mappings` table.

**Preconditions:**
- Caller has `ROLE_ADMIN` (or a role that grants `IMPORT_DATA`).
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
9. The existing `GET /rest/api/2/common/dependency-aliases` response is unaffected by v4 CRUD operations (reads same table).

**Test method:** —

**Out of scope:**
- Bulk import/replace-all (single-entry operations are sufficient; bulk is handled by `/admin/migrate-history`).
- Portal UI — not planned; the v2 read endpoint and direct API calls cover the use cases.
- Pagination — the mapping set is small enough for a flat list response.

---

### SYS-038: Domain-named meta endpoints for free-form aspect option lists

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
Schema-v2 (PR #192) reshaped the v4 contract such that `BuildAspect.buildSystem`, `VcsEntry.repositoryType`, and `Escrow.generation` are now free-form `string` fields rather than enum-typed. The canonical valid-token sets still live as Kotlin enums (used by `EntityMappers` on write parsing and read mapping) but are no longer reachable from the v4 typed contract. On a fresh CRS install the Portal's `EnumSelect` dropdown for these fields collapses to "None + current value" because no field-config seed is shipped, leaving users unable to change Build System / Repository Type / Escrow Generation after a value has been set.

**Description:**
Add three GET endpoints under `/rest/api/4/components/meta/*` that return the canonical option lists as `string[]`. Names are domain-named — NOT implementation-named (no `/meta/enums`) — so the wire surface survives a future move of the option source from a Kotlin enum to a config table or admin-editable registry without breaking the contract.

| Endpoint | Source enum |
|---|---|
| `GET /rest/api/4/components/meta/build-systems` | `org.octopusden.octopus.escrow.BuildSystem` |
| `GET /rest/api/4/components/meta/repository-types` | `org.octopusden.octopus.escrow.RepositoryType` |
| `GET /rest/api/4/components/meta/escrow-generations` | `org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode` |

For `buildSystem` and `repositoryType` the **persistence-layer** enums (NOT the `core.dto.*` mirrors) are the source. The DTO variant of `BuildSystem` carries `NOT_SUPPORTED` while persistence carries `ESCROW_NOT_SUPPORTED`; advertising the DTO token would silently drop user input on save because `EntityMappers.safeParseBuildSystem` calls `BuildSystem.valueOf` against the escrow variant. For `generation` the source is `components-registry-api`'s `EscrowGenerationMode` (the `core.dto.EscrowGenerationMode` mirror has the same token set; the API enum is the one `Mappers.toDTO()` reads off the escrow model).

Auth: `@PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")` — same gate as `/meta/owners`. `ACCESS_COMPONENTS` is granted to `ROLE_ANONYMOUS` in the current security config, matching the rest of the v4 component-read surface, so anonymous callers can read the option lists.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS` (granted to `ROLE_ANONYMOUS` by default — no JWT required).
- No DB state required — endpoint reads from compile-time enum metadata.

**Acceptance criteria:**
1. `GET /meta/build-systems` returns HTTP 200 with a JSON array equal to `escrow.BuildSystem.values().map { it.name }` in declaration order.
2. The returned array MUST contain `ESCROW_NOT_SUPPORTED` and MUST NOT contain `NOT_SUPPORTED` — guards against accidental reversion to the DTO-mirror enum.
3. `GET /meta/repository-types` returns HTTP 200 with a JSON array equal to `escrow.RepositoryType.values().map { it.name }` in declaration order (CVS, MERCURIAL, GIT).
4. `GET /meta/escrow-generations` returns HTTP 200 with a JSON array equal to `api.enums.EscrowGenerationMode.values().map { it.name }` in declaration order (AUTO, MANUAL, UNSUPPORTED).

**Test method:** `MetaOptionsEndpointsTest` — four cases covering criteria 1–4 against the live controller on the `ft-db` profile.

**Out of scope:**
- Admin write API for the option lists (the field-config registry `options[]` already exists; SYS-038 only addresses the canonical-set advertisement when admin has not seeded explicit options).
- Portal UI for editing the option lists (admin field-config write surface is unchanged).
- A 4th endpoint for `productType` — the existing flat-flat `field-config.options[]` channel is sufficient there.

---

### SYS-040: GET /components?labels=A,B + GET /components/meta/labels

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The Portal `ComponentListPage` filter bar already supports system, productType, buildSystem, owner, archived and search, but cannot narrow by **labels** even though every `ComponentSummary` carries `labels: string[]` and the table renders them. Users either eyeball-scan or fall back to `?search` (which matches `name`, not labels). To populate a multi-select picker the Portal also needs a `/meta/labels` source list — sourced from the `component_labels` junction so the picker advertises labels actually in use, parity with `/meta/owners`.

**Description:**
Two additions to the v4 component surface.

1. **`GET /rest/api/4/components?labels=A,B`** — multi-value AND filter. A returned component must carry every selected label. CSV is the primary wire format (Portal always sends CSV); Spring's binder also accepts repeatable params (`?labels=A&labels=B`). The controller normalises both shapes before populating the filter DTO:
   ```kotlin
   labels
       ?.flatMap { it.split(",") }
       ?.map { it.trim() }
       ?.filter { it.isNotEmpty() }
       ?.distinct()
       ?.takeIf { it.isNotEmpty() }
   ```
   `?labels=`, `?labels=,,`, and `?labels=,A,,B,` all canonicalise to the same shape as `?labels=A,B` (or "no filter" when the result is empty); `?labels=A,A` dedupes to a single-element list. AND semantics in the JPA Specification is implemented as one join + one predicate per label code — a single join + `IN(...)` would silently relax to OR.

2. **`GET /rest/api/4/components/meta/labels`** — returns sorted distinct label codes currently attached to at least one component, sourced from the `component_labels` junction via a new repository method `ComponentLabelRepository.findDistinctLabelCodes()`. NOT sourced from the master `LabelEntity` table, which may contain orphan codes that no component carries — advertising those would create dead options in the Portal picker. Mirrors `/meta/owners` in shape and intent.

   **Dictionary variant (UI-swift-sloth):** `GET /rest/api/4/components/meta/labels/dictionary` and `GET /rest/api/4/components/meta/systems/dictionary` expose **every row** of the master `labels` / `systems` tables, sorted ascending. The Portal's editor multi-select uses the dictionary variant so admin-seeded codes that no component carries yet are still selectable. The legacy junction-sourced variants (`/meta/labels`, `/meta/systems`) stay as-is for the filter-bar pickers, which deliberately want in-use values only.

Auth: both gated by `ACCESS_COMPONENTS` (granted to `ROLE_ANONYMOUS` by default), matching the rest of the v4 read surface and parity with `/meta/owners`.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.
- Database accessible (junction table `component_labels` populated by component creation / PATCH flows).

**Acceptance criteria:**
1. `GET /components?labels=A` returns a page whose every entry carries label A; components without A are excluded.
2. `GET /components?labels=A,B` returns a page whose every entry carries BOTH A and B (AND across selections); components carrying only one of the two are excluded.
3. `GET /components?labels=<unknown>` returns 200 + empty page (NOT 500, NOT an unfiltered list).
4. Blank normalisation: `?labels=`, `?labels=,,`, and any input that normalises to zero non-blank tokens behaves as "no labels filter".
5. Interleaved-blanks: `?labels=,A,,B,` behaves as `?labels=A,B`.
6. Whitespace trim: `?labels=A%20` and `?labels=%20A` both match label A.
7. Pagination and sort still apply when `?labels` is set (regression).
8. `GET /components/meta/labels` returns 200 + sorted distinct label codes; no duplicates even when multiple components carry the same code.
9. `GET /components/meta/labels` returns 200 + JSON array (NOT 404) when no labels exist — the Portal's `useLabels` 404/501 fallback is for the transitional pre-deploy window only; steady state must hit the happy path.
10. Write-side canonicalisation: labels are trimmed + deduped on `POST /components` and `PATCH /components/{id}`. Persisting `labels=["A "]` stores `"A"` (canonical) so the read-side filter contract holds; `labels=["A","A"," A "]` stores `["A"]`. Non-empty input that canonicalises to zero entries (e.g. `labels=[" "]`) is rejected with 400 — an empty `labels: []` is still a legitimate "clear labels" operation.

**Test method:** `ListComponentsLabelsFilterTest` covers criteria 1–7; `MetaOptionsEndpointsTest` (extended) covers criteria 8–9; `V4WriteValidationTest` (extended) covers criterion 10.

**Out of scope:**
- Sort-by-label or group-by-label.
- Saving filter state to URL (no existing filter does this; keep parity).
- Labels mutation UX (creating / renaming labels) — not requested.

---

### SYS-041: GET /components?buildSystem=GRADLE,MAVEN multi-value with OR semantics

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The Portal `ComponentListPage` previously supported only a single-value `?buildSystem=` selector. Users frequently need to scope to "any of a small set" (e.g., GRADLE OR MAVEN — both JVM build systems) and currently have to either pick one and miss the rest, or clear the filter entirely. Extending the existing query parameter to accept a CSV list with OR semantics turns the picker into a true multi-select.

**Description:**
Extend the v4 component listing surface so `?buildSystem=A,B,…` returns the union of components whose BASE configuration row's `buildSystem` column equals any of the listed values. Semantics is OR — a component has exactly one BASE `buildSystem` at a time, so AND across two distinct values would always yield zero matches and is not the intended behaviour.

Wire format primary is CSV (`?buildSystem=GRADLE,MAVEN`); Spring's binder also accepts repeatable params (`?buildSystem=GRADLE&buildSystem=MAVEN`). The controller normalises both shapes through the same pipeline used for labels:
```kotlin
buildSystem
    ?.flatMap { it.split(",") }
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()
    ?.takeIf { it.isNotEmpty() }
```
The Specification uses one JOIN through `configurations` with `rowType=BASE` and a single `IN (?,?,…)` predicate — mirrors the pre-multi-value shape (same JOIN, same rowType guard) but swaps the scalar `equal` for a collection IN. Single-value input still works as a degenerate IN.

The change is wire-compatible: a single value `?buildSystem=GRADLE` round-trips through the new pipeline unchanged. The DTO field type changed from `String?` to `List<String>?` but ComponentFilter is server-internal and not part of any external API surface.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.

**Acceptance criteria:**
1. `?buildSystem=GRADLE` (single value, backward compat) returns GRADLE components only.
2. `?buildSystem=GRADLE,MAVEN` (CSV) returns components whose BASE buildSystem is GRADLE OR MAVEN; a component on a third buildSystem (e.g., WHISKEY) is excluded.
3. `?buildSystem=GRADLE&buildSystem=MAVEN` (repeatable params) returns the same set as the CSV form.
4. `?buildSystem=<unknown>` returns 200 + empty page.
5. Blank normalisation: `?buildSystem=`, `?buildSystem=,,`, any input normalising to zero non-blank tokens behaves as "no buildSystem filter".
6. Interleaved-blanks: `?buildSystem=,GRADLE,,MAVEN,` behaves as `?buildSystem=GRADLE,MAVEN`.
7. Whitespace trim: `?buildSystem=GRADLE%20` and `?buildSystem=%20GRADLE` both match GRADLE.
8. Dedupe: `?buildSystem=GRADLE,GRADLE` behaves identically to `?buildSystem=GRADLE`.
9. Pagination and sort still apply when `?buildSystem=` is multi-valued (regression). Returned `content[].name` is sorted ascending when `sort=componentKey,asc` is set.

**Test method:** `ListComponentsBuildSystemFilterTest` — extended with nine SYS-041 cases covering criteria 1–9 alongside the existing single-value tests.

**Out of scope:**
- Sort-by-buildSystem, group-by-buildSystem.
- AND across buildSystems (vacuously empty given the schema, so not useful).
- Validation of unknown values at the controller (write-side already validates via `BUILD_SYSTEM_NAMES`; read-side just returns empty for unknowns, mirroring the pre-multi-value behaviour).

---

### SYS-042: GET /components?system=A,B multi-value with OR semantics + GET /components/meta/systems

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The Portal `ComponentListPage` previously supported only a single-value `?system=` selector. Like buildSystem, users often want to scope to "any of a small set" of systems (e.g., CLASSIC OR a sibling code). Extending the existing query parameter to accept CSV multi-value turns the picker into a true multi-select. The companion `/meta/systems` endpoint populates the picker from labels in use (parity with `/meta/owners` and `/meta/labels`).

**Description:**
Two additions to the v4 component surface, mirroring SYS-040 (labels) and SYS-041 (buildSystem).

1. **`GET /rest/api/4/components?system=A,B`** — multi-value OR filter over the `component_systems` M:N junction. A component matches when ANY of its system memberships is in the list. The Specification JOINs `systemJunctions` + `systemCode IN (...)` + `query.distinct(true)` (distinct dedupes the row multiplication a multi-system component would otherwise cause). CSV is the primary wire format; Spring also accepts repeatable params. The controller normalises both shapes through the same pipeline used for labels and buildSystem (split → trim → filter empty → distinct → null-if-empty).

2. **`GET /rest/api/4/components/meta/systems`** — returns sorted distinct system codes currently attached to at least one component, sourced from the `component_systems` junction via `ComponentSystemRepository.findDistinctSystemCodes()`. Same blank/null defence as `findDistinctLabelCodes` and `findDistinctOwners`. Mirrors `/meta/owners` and `/meta/labels` in shape and intent.

Auth: both gated by `ACCESS_COMPONENTS`, matching the rest of the v4 read surface.

The change is wire-compatible: a single value `?system=CLASSIC` round-trips through the new pipeline unchanged. The DTO field type changed from `String?` to `List<String>?` but `ComponentFilter` is server-internal and not part of any external API surface.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.

**Acceptance criteria:**
1. `?system=A` (single value, backward compat) returns components carrying system A.
2. `?system=A,B` (CSV) returns components carrying A OR B; components carrying only a third code are excluded.
3. `?system=A&system=B` (repeatable params) returns the same set as the CSV form.
4. `?system=<unknown>` returns 200 + empty page (existing behaviour, regression-checked).
5. Blank normalisation: `?system=`, `?system=,,` and any input normalising to zero non-blank tokens behaves as "no system filter".
6. Interleaved-blanks: `?system=,A,,B,` behaves as `?system=A,B`.
7. Whitespace trim: `?system=A%20` and `?system=%20A` both match A.
8. Dedupe: `?system=A,A` behaves identically to `?system=A`.
9. Pagination and sort still apply when `?system=` is multi-valued. Returned `content[].name` is sorted ascending when `sort=componentKey,asc` is set.
10. `GET /meta/systems` returns 200 + sorted distinct system codes; no duplicates even when multiple components carry the same code.
11. `GET /meta/systems` always returns 200 + a JSON array (NOT 404), regardless of DB state.

**Test method:** `ListComponentsSystemFilterTest` (extended with SYS-042 cases) covers criteria 1–9 alongside the existing single-value tests; `MetaOptionsEndpointsTest` (extended) covers criteria 10–11.

**Out of scope:**
- Sort-by-system, group-by-system.
- AND across systems (could be useful but not requested — picker UX is OR).
- A dedicated empty-DB contract test class for `/meta/systems` (parallel to `MetaLabelsEmptyDbContractTest`) — can be added later if needed; current `/meta/systems` shape test asserts always-200-array against the seeded context.

---

### SYS-043: GET /components?owner=alice,bob multi-value with OR semantics

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
SYS-035 introduced the single-value `?owner=` filter. The Portal `ComponentListPage` owner picker is now a multi-select (parity with the labels, system, and buildSystem multi-selects), so the wire contract needs to accept a CSV list with OR semantics — "components owned by any of these people".

**Description:**
Extend `?owner=` to accept CSV multi-value (and repeatable params). The controller normalisation pipeline is identical to the other multi-value filters (split → trim → filter empty → distinct → null-if-empty). `componentOwner` is a scalar column on `ComponentEntity` (NOT a junction), so the Specification needs no JOIN and no `query.distinct(true)` — a single `root.get<String>("componentOwner").in(filter.owner)` predicate is the entire branch. This is the simplest of the multi-value filter shapes by construction.

The change is wire-compatible: a single value `?owner=alice` round-trips through the new pipeline unchanged. DTO field type `String?` → `List<String>?` is server-internal.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.

**Acceptance criteria:**
1. `?owner=alice` (single value, backward compat) returns components owned by alice only.
2. `?owner=alice,bob` (CSV) returns components owned by alice OR bob; components owned by a third user are excluded.
3. `?owner=alice&owner=bob` (repeatable params) returns the same set as the CSV form.
4. Blank normalisation: `?owner=`, `?owner=,,` and any input normalising to zero non-blank tokens behaves as "no owner filter".
5. Interleaved-blanks: `?owner=,alice,,bob,` behaves as `?owner=alice,bob`.
6. Whitespace trim: `?owner=alice%20` and `?owner=%20alice` both match alice.
7. Dedupe: `?owner=alice,alice` behaves identically to `?owner=alice`.
8. Pagination and sort still apply when `?owner=` is multi-valued. Returned `content[].name` is sorted ascending when `sort=componentKey,asc` is set.

**Test method:** `ListComponentsOwnerFilterTest` — extended with nine SYS-043 cases covering criteria 1–8 alongside the existing single-value SYS-035 tests.

**Out of scope:**
- Group-by-owner.
- AND across owners (would always yield zero matches given the scalar column; not useful).

### SYS-044: Multi-value releaseManager / securityChampion (ordered, deduped)

**Priority:** High
**Test layer:** unit + integration-test
**Status:** ✅ Tested

**Motivation:**
A component can have more than one release manager / security champion, and the
order is meaningful (first = primary). Previously these were single scalar
`VARCHAR(255)` columns on `components` surfaced as `String?` in v4. The DSL
already stores each as a comma-separated string validated by `\w+(,\w+)*`.

**Description:**
- DB: `release_manager` / `security_champion` scalar columns are **dropped**;
  two ordered child tables `component_release_managers` /
  `component_security_champions` (surrogate UUID PK, `username`, `sort_order`,
  `component_id` FK ON DELETE CASCADE) hold the lists. See schema-spec.md §4.1/§4.6.
- v4 API: `ComponentDetailResponse` / `ComponentCreateRequest` expose
  `List<String>` (default `emptyList()`); `ComponentUpdateRequest` uses
  `List<String>?` (`null` = don't touch, provided list incl. empty = replace
  whole ordered list).
- Canonicalization (single point — `ComponentEntity.replace*Usernames`): trim →
  drop blank → keep-first dedupe, applied uniformly to create, patch, and
  import. Order preserved.
- Legacy v1/v2/v3 stay **non-breaking**: `EntityMappers` joins the ordered list
  back into a comma-string (empty → null); `ImportServiceImpl.buildComponentEntity`
  splits the DSL CSV into the ordered list. No `component-resolver-core` / DSL /
  `EscrowModuleConfig` changes.
- `componentOwner` cardinality is **unchanged** (single-value scalar everywhere);
  the only owner change is removing it (with releaseManager / securityChampion)
  from the global `component-defaults` surface (`migrateDefaults` + the admin
  defaults form), because `Defaults.groovy` never sets people fields.
- Audit: `scalarAuditMap` emits the comma-joined value (empty → null), same rule
  as the legacy mapper. The Git-history snapshot serializer is unaffected (it
  reads the DSL CSV string straight from the loader).

**Acceptance criteria:**
1. Create / patch / import all collapse `[" alice ", "", "alice", "bob"]` →
   `["alice", "bob"]` (trim → drop-blank → keep-first dedupe, order preserved).
2. v4 PATCH with a list replaces the whole ordered list; empty list clears;
   `null`/absent does not touch the stored list.
3. Reordering round-trips (order is meaningful).
4. Legacy resolve joins the ordered list to a comma-string; empty → null;
   single value `"user"` round-trips unchanged.
5. The Git-history snapshot preserves the DSL CSV string for a multi-value
   component.
6. A multi-value PATCH audits the comma-joined value in `scalarAuditMap`.

**Test method:** every method below carries `SYS-044` in its name + a
`@DisplayName("SYS-044: …")` (test-to-requirement traceability).
- `ComponentPeopleAccessorTest` — `SYS-044 replaceReleaseManagerUsernames preserves order and assigns sortOrder by index`,
  `SYS-044 releaseManagerUsernames sorts by sortOrder not collection or heap order`,
  `SYS-044 release manager canonicalization trim drop-blank keep-first dedupe`,
  `SYS-044 security champion canonicalization trim drop-blank keep-first dedupe`,
  `SYS-044 empty list clears the ordered collection`,
  `SYS-044 replace re-numbers sortOrder from 0 on reorder`,
  `SYS-044 release manager and security champion lists are independent`.
- `ImportServicePeopleCsvSplitTest` — `SYS-044 import splits releaseManager CSV into ordered list`,
  `SYS-044 import splits securityChampion CSV into ordered list`,
  `SYS-044 import is lenient about the spaced validator-invalid form`,
  `SYS-044 import canonicalizes trim drop-blank keep-first dedupe`,
  `SYS-044 import single-value CSV round-trips to one-element list`,
  `SYS-044 import null CSV yields empty ordered list`.
- `MultiValuePeopleLegacyCompatTest` — `SYS-044 multi-value lists join back to the original DSL comma-string`,
  `SYS-044 empty people lists join to null not empty string`,
  `SYS-044 single-value list round-trips to the same string`.
- `MultiValuePeopleV4Test` (ft-db HTTP) — `SYS-044 CREATE canonicalizes people`,
  `SYS-044 PATCH replaces the whole ordered list`,
  `SYS-044 PATCH with empty list clears the ordered list`,
  `SYS-044 PATCH null does not touch the stored list`,
  `SYS-044 PATCH canonicalizes people the same way as create`,
  `SYS-044 PATCH preserves reordering`,
  `SYS-044 PATCH audit composes the comma-joined value`.
- `ComponentDetailMapperTest` — `SYS-044 multi-value people map to ordered lists with sortOrder preserved`.
- `Sys039PersistenceRoundtripTest.SYS-039: all six new scalar/array fields round-trip via PATCH + GET`
  — extended to the `string[]` shape (array PATCH+GET round-trip).
- `ComponentHistorySnapshotSerializerTest.multi-value DSL releaseManager and securityChampion preserve the CSV string in the snapshot`
  — characterization guard for the Git-history path (no production change, plan §6).

**Out of scope:**
- Filtering by releaseManager / securityChampion (not requested).
- `componentOwner` multi-value (stays single).

---

### SYS-045: GET /components?distributionExplicit= / ?distributionExternal= activate the distribution boolean filters

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The Portal extended-search bar gains two distribution toggles (`distributionExplicit`, `distributionExternal`) — the pair that, when both true, drives the DMS link and the conditionally-required RM/SC/copyright validation (see the functional-spec distribution rules). The two `@RequestParam`s and the `ComponentFilter` fields were wired in an earlier WIP commit, but `buildSpecification` had **no matching predicate**, so `?distributionExplicit=…` was silently inert — it returned the full list rather than narrowing it. This requirement closes that gap.

**Description:**
Two scalar boolean filters on the `components.distribution_explicit` / `components.distribution_external` columns, mirroring the existing `solution` filter exactly. Each branch is a plain `cb.equal(root.get<Boolean>("distributionExplicit"/"distributionExternal"), value)` — no JOIN, no `query.distinct(true)`. Both columns are nullable, so `…=false` matches only rows explicitly set to `false`; rows where the value `IS NULL` (never set) are excluded — identical to `solution` semantics. Absent param = no distribution filter.

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.

**Acceptance criteria:**
1. `?distributionExplicit=true` returns only components whose `distributionExplicit` is `true`; a component with `distributionExplicit=false` is excluded.
2. `?distributionExternal=true` returns only components whose `distributionExternal` is `true`; a `distributionExternal=false` component is excluded.
3. `?distributionExplicit=false` excludes rows where the value `IS NULL` (never set), matching `solution` semantics.
4. Absent param = no distribution filter (regression).

**Test method:** `ListComponentsExtendedFiltersTest` — `` `SYS-045 distributionExplicit filter returns only explicit components`() `` and `` `SYS-045 distributionExternal filter returns only external components`() ``.

**Out of scope:**
- Tri-state wire encoding (the nullable `Boolean` param already covers true / false / absent).
- Multi-value distribution filtering (a boolean has at most two values; multi-select is meaningless).

---

### SYS-046: clientCode / jiraProjectKey / parentComponentName / groupKey multi-value exact-IN + companion in-use meta endpoints

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The first cut of the extended-search bar (functional-spec §Filters) backed `clientCode`, `jiraProjectKey`, `parentComponentName` and `groupKey` with free-text inputs — `clientCode` / `jiraProjectKey` / `groupKey` matched by case-insensitive `LIKE`, `parentComponentName` by exact equality. The Portal is moving these four to **multi-select dropdowns** sourced from the values actually in use (parity with the `owner` / `system` / `labels` pickers). A dropdown of real values wants exact-match OR semantics ("any of the selected values"), not substring search, so the four filters become multi-value exact-`IN`, and four companion `/meta/*` endpoints supply the distinct in-use option lists.

**Description:**
Mirrors SYS-040 (labels) / SYS-042 (system): a filter change plus a companion in-use meta endpoint, for four fields at once.

1. **Filters** — the four `ComponentFilter` fields change `String?` → `List<String>?`; the controller `@RequestParam`s change to `List<String>?` and are normalised through the same `normalizeCsvParam` pipeline (split → trim → drop-empty → distinct → null-if-empty) already used by `owner` / `system` / `buildSystem` / `labels`. `buildSpecification` swaps each scalar/`LIKE` branch for an exact-`IN`:
   - `clientCode` → `root.get<String>("clientCode").in(values)` — scalar column, no JOIN, no distinct. **Behaviour change: substring `LIKE` → exact `IN`.**
   - `jiraProjectKey` → one JOIN through `configurations` (`rowType=BASE`) + `cfg.get("jiraProjectKey").in(values)` + `distinct(true)`. **Behaviour change: substring `LIKE` → exact `IN`.**
   - `parentComponentName` → JOIN `parentComponent` + `parent.get("componentKey").in(values)` — ManyToOne, no distinct. (Was already exact; just widened to multi-value.)
   - `groupKey` → JOIN `componentGroup` + `group.get("groupKey").in(values)` — ManyToOne, no distinct. **Behaviour change: substring `LIKE` → exact `IN`.**
   The change is wire-compatible for the single-value case (`?clientCode=ACME` is a degenerate one-element `IN`). `ComponentFilter` is server-internal and not part of any external API surface.

2. **Meta endpoints** (all gated by `ACCESS_COMPONENTS`, parity with `/meta/owners`) — each returns sorted distinct values **actually in use**, null/blank-filtered:
   - `GET /rest/api/4/components/meta/client-codes` → `ComponentRepository.findDistinctClientCodes()` (scalar `components.client_code`).
   - `GET /rest/api/4/components/meta/jira-project-keys` → `ComponentRepository.findDistinctJiraProjectKeys()` (distinct `jira_project_key` over BASE configuration rows).
   - `GET /rest/api/4/components/meta/parent-component-names` → `ComponentRepository.findDistinctParentComponentNames()` (distinct `component_key` of components actually referenced as someone's parent — NOT the can-be-parent candidate set, which the editor parent picker uses via `?canBeParent=true`).
   - `GET /rest/api/4/components/meta/group-keys` → `ComponentGroupRepository.findDistinctGroupKeys()` (distinct `group_key` of groups that own ≥1 component, so no dead options).

**Preconditions:**
- Caller has `ACCESS_COMPONENTS`.

**Acceptance criteria:**
1. `?clientCode=ACME` returns only components whose `clientCode` is exactly `ACME` (no longer a substring match).
2. `?clientCode=ACME,BETA` returns components whose `clientCode` is `ACME` OR `BETA`; a third code is excluded.
3. `?jiraProjectKey=AAA,BBB` returns components whose BASE `jira_project_key` is `AAA` OR `BBB` (exact); a multi-VCS / multi-config component is counted once (distinct).
4. `?parentComponentName=p1,p2` returns children of `p1` OR `p2`; a standalone component is excluded.
5. `?groupKey=g1,g2` returns components whose owning group key is exactly `g1` OR `g2`.
6. Blank / interleaved-blank / dedupe / whitespace-trim normalisation matches the other multi-value filters (`?clientCode=`, `?clientCode=,,`, `?clientCode=A,A`).
7. `GET /meta/client-codes` / `/meta/jira-project-keys` / `/meta/parent-component-names` / `/meta/group-keys` each return 200 + a sorted, duplicate-free JSON array of in-use values, never 404, regardless of DB state.
8. `/meta/parent-component-names` lists only component keys actually referenced as a parent; `/meta/group-keys` lists only group keys with ≥1 member.

**Test method:** `ListComponentsExtendedFiltersTest` (the `clientCode` / `jiraProjectKey` / `parentComponentName` / `groupKey` cases updated to exact-IN + a multi-value case) and `MetaInUseOptionsEndpointsTest` (the four endpoints).

**Out of scope:**
- Converting the remaining extended scalars (`vcsPath`, `productionBranch`) to multi-value — they stay single-value `LIKE` (free-text search, not dropdown-backed).
- URL/bookmark state for the filters (Portal holds filter state in React, not query params).

### SYS-047: git-only no-DB boot mode

**Priority:** High
**Test layer:** unit / context-load test
**Status:** ✅ Tested

**Motivation:**
The `[1.8]` git-mode compat stand (id18) runs the candidate with
`auto-migrate=false` + `default-source=git`, serving every v1/v2/v3 request from
the Git resolver. Before this change the candidate still booted a full JPA +
Flyway + Hikari stack against Postgres purely because the active profile supplied a
datasource — the DB was never read for serving. The stand therefore had to start
Postgres for nothing (follow-up from #308 / #309, issue #310).

**Description:**
- New `no-db` Spring profile (`application-no-db.yml`) — the single switch. It (a)
  sets `spring.autoconfigure.exclude` for `DataSourceAutoConfiguration`,
  `DataSourceTransactionManagerAutoConfiguration`, `HibernateJpaAutoConfiguration`,
  `JpaRepositoriesAutoConfiguration`, `FlywayAutoConfiguration`, and (b) sets
  `components-registry.database.enabled=false` (plus `auto-migrate=false`,
  `default-source=git`, `teamcity.sync.enabled=false`).
- New `components-registry.database.enabled` flag (default `true`) and the
  `@ConditionalOnDatabaseEnabled` meta-annotation
  (`@ConditionalOnProperty(..., matchIfMissing = true)`) gate every DB-coupled bean:
  the DB resolver, `ComponentSourceRegistry`, the `@Primary` routing resolver, the
  import / component-management / audit / field-config / git-history / migration /
  TeamCity-sync services, the TeamCity scheduler, the migration executor, the audit +
  field-config listeners, and the v4 CRUD / admin / audit / config controllers. With
  the flag unset, db-mode (id17) is unchanged.
- The pure-Git `ComponentRegistryResolverImpl` already implements the full
  `ComponentRegistryResolver` interface; when the `@Primary` routing resolver drops
  out it becomes the **sole** resolver, so every v1/v2/v3 consumer binds to it — the
  same code path as the 2.0.87 baseline.
- The two git-path beans that inject DB deps (`ComponentsRegistryServiceImpl`,
  `ComponentsRegistryServiceController`) take them as Kotlin-nullable (optional)
  dependencies: status `dbComponentCount` is null-safe (0), and `updateCache` keeps
  its per-pod refresh lock in **both** modes but skips the DB-only migration-status
  / 410-retirement gate when no `ImportService` is wired.
- Local stand: `teamcity-run.sh` / `candidate.sh` run git-mode with the `no-db`
  profile and do NOT start Postgres (id18 drops the `POSTGRES_IMAGE` pull); db-mode
  (id17) is untouched.

**Acceptance criteria:**
1. With the `no-db` profile active the context starts with **no `DataSource` bean**
   and no Hikari/Flyway — no database is required.
2. The `@Primary` `ComponentRoutingResolver` and all DB-coupled beans (v4 CRUD,
   TeamCity sync, …) are absent; the sole `ComponentRegistryResolver` is the
   pure-Git `ComponentRegistryResolverImpl`.
3. `GET /rest/api/2/components-registry/service/status` reports `defaultSource=git`
   and `dbComponentCount=0`.
4. db-mode (no `no-db` profile) is unchanged — every gated bean is `matchIfMissing=true`.

**Test method:** `NoDbModeContextTest` — four methods, each carrying `SYS-047` in
its name + a `@DisplayName("SYS-047: …")` (test-to-requirement traceability):
`SYS-047 no DataSource bean exists in no-db mode`,
`SYS-047 git resolver is the sole resolver in no-db mode`,
`SYS-047 db-only beans absent and git read path present in no-db mode`,
`SYS-047 status reports defaultSource git and zero db components in no-db mode`.

### SYS-048: no-op save writes no audit row

**Priority:** High
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
Clicking Save on an unmodified edit form (and any other write path that re-saves
without changing anything) used to append an `UPDATE` audit row whose
`change_diff` was empty. These "saved, changed nothing" rows are noise that
hides the real history.

**Description:**
`AuditEventListener` computes the field-level diff (`AuditDiff.compute`) and, when
**both** `oldValue` and `newValue` are present but the diff is empty, skips
persisting the row entirely. This is centralised in the listener, so it applies to
every publisher: component `UPDATE`, field-override writes (SYS-050), and TeamCity
sync. `CREATE` (null `oldValue`) and `DELETE` legitimately produce a null diff and
are always kept.

**Acceptance criteria:**
1. An `UPDATE` event whose `oldValue` equals `newValue` (empty diff) is not persisted.
2. A `CREATE` event (null `oldValue`) is persisted even though its diff is null.
3. A real `UPDATE` (non-empty diff) is persisted with the computed `change_diff`.

**Test method:** `AuditEventListenerTest` — `SYS-048 no-op UPDATE writes no audit row`,
`SYS-048 real UPDATE is persisted`, `SYS-048 CREATE is always persisted`,
`SYS-048 DELETE is persisted`.

### SYS-049: git-history baseline recorded as MIGRATED and hidden by default

**Priority:** High
**Test layer:** unit + integration-test
**Status:** ✅ Tested

**Motivation:**
The git-history backfill writes one row per component for the commit where it first
appears. Recording these as `CREATE` floods the audit views with migration noise
that carries no operational value and misrepresents a migration as a user action.

**Description:**
- `GitHistoryImportServiceImpl` resolves a component's first appearance
  (`oldValue == null`) to `AuditLogEntity.ACTION_MIGRATED` (`"MIGRATED"`) instead of
  `CREATE`. Genuine historical `UPDATE`/`DELETE` rows are unchanged. Runtime API
  creates (via `ComponentManagementServiceImpl`) remain `CREATE`.
- Both audit read endpoints hide `MIGRATED` rows by default. `GET /audit/recent`
  and `GET /audit/{entityType}/{entityId}` accept `includeMigrated` (default
  `false`); `getEntityHistory` uses a no-`MIGRATED` query on the default path and the
  recent-filter adds a `action != MIGRATED` predicate. `/audit/recent` (the only
  endpoint with an `action` query param) additionally returns `MIGRATED` rows when
  an explicit `action=MIGRATED` filter is given, regardless of `includeMigrated`;
  the entity-history endpoint has no `action` param, so `includeMigrated` is its
  sole opt-in. Either way the Portal "Show migration" toggle surfaces them.

**Acceptance criteria:**
1. A first-appearance snapshot resolves to `MIGRATED`; disappearance → `DELETE`;
   changed → `UPDATE`; unchanged → null (no row).
2. `GET /audit/recent` and `GET /audit/Component/{id}` exclude `MIGRATED` rows by default.
3. `includeMigrated=true` surfaces `MIGRATED` rows on both endpoints.
4. On `GET /audit/recent`, an explicit `action=MIGRATED` filter returns `MIGRATED` rows even without `includeMigrated` (the entity-history endpoint has no `action` param).

**Test method:** `GitHistoryImportActionResolutionTest` (`SYS-049 first appearance
resolves to MIGRATED` and the DELETE/UPDATE/null cases) +
`AuditMigratedVisibilityTest` (`SYS-049 recent hides MIGRATED by default`,
`SYS-049 recent includeMigrated returns MIGRATED`,
`SYS-049 explicit action MIGRATED wins over default hide`,
`SYS-049 entity history honours includeMigrated`).

### SYS-050: field-override writes are audited as Component UPDATE

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
Adding, changing or removing a version-ranged attribute override is a real change to
the component, but the field-override write paths only bumped the parent version —
they published no audit event, so version-range edits were invisible in the
component history (inconsistent with top-level attribute edits, which are audited).

**Description:**
`createFieldOverride` / `updateFieldOverride` / `deleteFieldOverride` publish a
Component `UPDATE` `AuditEvent` (`entityId` = the parent component id) with an
old/new snapshot keyed by the overridden attribute (`fieldOverride[<attr>]`),
carrying the version range and the resolved scalar value / marker children. A no-op
override PATCH produces an empty diff and is dropped by the SYS-048 guard.

**Acceptance criteria:**
1. `createFieldOverride` writes one Component `UPDATE` audit row whose diff names the
   overridden attribute.
2. `updateFieldOverride` (real change) and `deleteFieldOverride` each write a
   Component `UPDATE` audit row.
3. A no-op override PATCH (unchanged value) writes no audit row.

**Test method:** `FieldOverrideAuditTest` —
`SYS-050 field-override writes are audited as Component UPDATE`,
`SYS-050 no-op override PATCH writes no audit row`.

### SYS-051: TeamCity sync writes no audit row

**Priority:** Medium
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
The TeamCity sync (`/admin/teamcity-project-ids/sync` and the scheduled cron) is an
automated reconciliation that links each component to its TeamCity project(s) — one
row per distinct `PROJECT_VERSION` release line (see technical-design §6.4.2 for the
resolution algorithm). Every re-link previously published a Component `UPDATE` `AuditEvent`
(`changedBy = system`, `teamcityProjectId` / `teamcityProjectUrl`). One such row
per re-linked component is operational noise in the component history — it is
not a user-initiated change.

**Description:**
`TeamcitySyncService.applyMatch` no longer publishes an `AuditEvent`; instead it
emits an INFO log line tracing the re-link (component id, the existing → desired
`(projectId, projectVersion)` row set, and the resolving user) so the source of a
write is still discoverable. No
`audit_log` row is written for a TeamCity sync. The `ApplicationEventPublisher`
seam is retained so the test can assert nothing is published (and as the
re-wire point if per-sync auditing is ever wanted).

**Acceptance criteria:**
1. A TeamCity sync that writes/updates a component's TC row publishes **no** `AuditEvent`.
2. The TC row + sync counts (`updated` / `unchanged` / …) are unaffected.
3. An unchanged component (idempotent re-run) still publishes nothing (unchanged behaviour).

**Test method:** `TeamcitySyncServiceTest` —
`SYS-051 happy path: writes TC row; counts updated; NO audit event`, plus the
`ambiguousAutoResolved` and `mixed batch` cases assert
`publisher.events.filterIsInstance<AuditEvent>().isEmpty()`.

### SYS-052: Unresolvable employee-service.url placeholder must not fail boot

**Priority:** High
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
The shared service configuration ships `employee-service.url` as a value
containing an environment-specific placeholder (an api-gateway hostname).
Environments that do not define that placeholder (notably the local
compatibility stands, where the candidate boots against a plain
service-config checkout) previously died during context refresh:
`EmployeeServiceUrlConfiguredCondition` resolved the property eagerly and the
unresolvable placeholder threw `IllegalArgumentException` out of
`Condition.matches`, failing the whole application instead of degrading. This
took down all compat gates ([1.7]/[1.8]/[1.9]) on 2026-06-07.

**Description:**
`EmployeeServiceUrlConfiguredCondition` treats a URL whose placeholder cannot
be resolved in the current environment exactly like a missing/blank URL: the
condition returns `false` (no `employeeServiceClient` bean is registered, the
active-employee check degrades to DISABLED — the documented fail-open
behaviour) and a WARN line records why. Context refresh succeeds.

**Acceptance criteria:**
1. `employee-service.enabled=true` + `employee-service.url` with an unresolvable placeholder ⇒ application context starts.
2. No `employeeServiceClient` bean is registered in that case.
3. A resolvable, non-blank URL still registers the client bean (unchanged behaviour).
4. A blank/missing URL still skips registration (unchanged behaviour).

**Test method:** `EmployeeServiceConfigTest` —
`SYS-052 unresolvable url placeholder skips bean registration` (plus the
pre-existing blank-url and non-blank-url cases covering criteria 3–4).

### SYS-053: Section-field PATCH writes an audit row (History no longer empty)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
Saving a section of the component form (e.g. Build tab → Maven Version 3.9 →
"Save Build") persisted the value but left the History tab empty. The audit
snapshots passed to `publishAuditEvent` captured only the component's top-level
scalars (`scalarAuditMap`), so a PATCH that changed only section data produced
identical `old_value` / `new_value` maps, `AuditDiff.compute` returned `null`,
and the SYS-048 no-op guard dropped the row. Component CREATE was unaffected
(a CREATE row is persisted without a diff), which made the bug read as
"creation shows in History, edits don't".

**Description:**
Both audit snapshots of the component CREATE / UPDATE (RENAME) events now
additionally include (`sectionAuditMap`):
- BASE-configuration aspect scalars under flat dotted keys — `build.*`
  (buildSystem, javaVersion, mavenVersion, gradleVersion, buildFilePath,
  deprecated, requiredProject, projectVersion, systemProperties, buildTasks),
  `escrow.*`, `jira.*`, plus `baseConfiguration.versionRange`;
- BASE-configuration child collections — `vcsEntries`, `mavenArtifacts`,
  `fileUrlArtifacts`, `dockerImages`, `packages`, `buildToolBeans`,
  `requiredTools`;
- per-component child collections — `artifactIds`, `securityGroups`,
  `teamcityProjects`, `docs`.

Collection snapshots are **content-only and deterministically ordered**
(`sortOrder`, or content where no `sortOrder` exists): a PATCH REPLACE
recreates child rows, so row ids in the snapshot would diff on id churn and
turn every re-save of an unchanged form into a fake history entry, violating
SYS-048. Version-ranged rows (SCALAR_OVERRIDE / MARKER) stay out of this
snapshot — their writes publish their own attribute-keyed events (SYS-050).

**Acceptance criteria:**
1. `PATCH /rest/api/4/components/{id}` changing only `baseConfiguration.build.mavenVersion` writes an `UPDATE` audit row whose `change_diff` carries `build.mavenVersion` with the correct old/new values.
2. A PATCH changing only `baseConfiguration.jira.projectKey` writes an `UPDATE` row keyed `jira.projectKey`.
3. A PATCH replacing `baseConfiguration.vcsEntries` writes an `UPDATE` row keyed `vcsEntries`.
4. A no-op section PATCH (same scalar value, or a byte-identical collection REPLACE) writes no audit row (SYS-048 holds — row-id churn must not leak into the diff).
5. The CREATE audit row's `new_value` includes the section keys.

**Test method:** `ComponentSectionAuditTest` —
`SYS-053 build scalar PATCH writes an UPDATE audit row with field-level diff`,
`SYS-053 jira scalar PATCH writes an UPDATE audit row`,
`SYS-053 vcsEntries PATCH writes an UPDATE audit row`,
`SYS-053 requiredTools PATCH writes an UPDATE audit row and identical re-send does not`,
`SYS-053 securityGroups PATCH writes an UPDATE audit row and identical re-send does not`,
`SYS-053 no-op section PATCH writes no audit row`.

### SYS-054: Audit rows expose a resolved componentKey

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
Audit rows store `entityId` as the component UUID, so the audit read API
exposed no human-readable key. Consumers (the Portal history table) had to dig
the key out of the value snapshot, which fails for two row shapes: git-history
`MIGRATED` snapshots key the component under `moduleName` (not `name`), and
field-override (SYS-050) snapshots carry only the override payload — no name at
all — leaving only the UUID. Those rows rendered an opaque UUID instead of a
component key.

**Description:**
`AuditLogResponse` gains a nullable `componentKey`. For Component rows the
service resolves it from the `entityId` UUID to the component's **current**
`componentKey`, batched once per page (a single `findAllById` over the page's
distinct UUIDs — no per-row query). When the component no longer exists
(`DELETE` / `MIGRATED` / hard-deleted) it falls back to the name captured in
the snapshot — `newValue.name` → `oldValue.name` → `newValue.moduleName` →
`oldValue.moduleName` — and a blank name normalizes to `null`. Non-Component
rows resolve to `null`. On `RENAME` rows the live key is the current
(post-rename) key by design; the prior key remains in `oldValue`.

**Acceptance criteria:**
1. A Component CREATE/UPDATE row exposes `componentKey` equal to the component's current key.
2. A field-override `UPDATE` row (snapshot has no `name`) exposes `componentKey` resolved from the live component — i.e. not the UUID.
3. A row for a deleted/migrated component (absent from the repository) falls back to the snapshot `name` / `moduleName`; a blank name yields `null`.
4. Rows whose `entityType` is not `Component` expose `componentKey = null`.
5. The field is `null`-not-`required` in the generated OpenAPI `v4.json`.

**Test method:**
`FieldOverrideAuditTest` — `SYS-054 field-override audit rows expose the resolved componentKey, not just the UUID entityId`: the override UPDATE rows belong to a live component, so this exercises the live-lookup path (criteria 1 and 2);
`AuditComponentKeyResolutionTest` —
`SYS-054 deleted component falls back to snapshot name` (criterion 3),
`SYS-054 migrated row falls back to snapshot moduleName` (criterion 3),
`SYS-054 blank snapshot name resolves to null` (criterion 3),
`SYS-054 non-component rows resolve to null` (criterion 4).
Criterion 5 is covered by `OpenApiV4SpecTest` (componentKey present in `v4.json`, absent from `required`).

### SYS-055: Anonymous migration-status probe for tokenless callers

**Priority:** Medium
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The portal runs a background validation sweep that calls CRS WITHOUT a JWT (no
user token on a scheduled sweep) and must skip while a migration is in flight:
mid Git→DB migration the legacy v2/v3 resolver can serve components with
not-yet-migrated `archived` flags, which the sweep would otherwise validate and
cache as spurious problems on already-archived components. The authoritative job
state is exposed only under the admin-gated `/rest/api/4/admin/**` API, which the
tokenless sweep cannot read — so there was no signal an unauthenticated caller
could use to detect an in-flight migration.

**Description:**
`GET /rest/api/4/migration-status` is an anonymous (permitAll) endpoint returning
`{ "running": Boolean, "kind": String? }`, derived from
`MigrationLifecycleGate.current()` — `running` is true iff a COMPONENTS, HISTORY,
or TC_RESYNC job currently holds the gate; `kind` is that job kind (informational,
null when idle). The body carries no component data or internal topology. It sits
on a permitAll path next to `GET /rest/api/4/info`, ordered before the
`/rest/api/4/**` authenticated catch-all so it does not widen any other route.

This endpoint is **transitional**: it only matters during the DSL→DB migration
era. Once all components are DB-native and the legacy migration/resync jobs are
retired, `MigrationStatusControllerV4` and its permitAll rule can be deleted.

Single-pod scope: `MigrationLifecycleGate` is an in-memory AtomicReference (same
caveat as the admin job endpoints) — with CRS replicas > 1 a probe may hit a
non-migrating pod. Making the gate DB-backed is the documented follow-up (MIG-028).

**Acceptance criteria:**
1. An unauthenticated (no JWT) `GET /rest/api/4/migration-status` returns `200 OK` (NOT 401) — the route is permitAll.
2. When no migration/resync job holds the lifecycle gate, the body is `{ "running": false }` (the `kind` key is omitted/null).
3. While a job holds the gate, the body is `{ "running": true, "kind": "<COMPONENTS|HISTORY|TC_RESYNC>" }`.
4. The body never carries component data, credentials, or internal topology.

**Test method:** `MigrationStatusControllerV4Test` —
`SYS-055 anonymous probe returns running false when gate is free`,
`SYS-055 anonymous probe returns running true with kind while a migration runs`.

### SYS-058: Explicit artifact-ID ownership model (modes) + deterministic cross-component uniqueness

**Priority:** High
**Test layer:** unit + integration-test
**Status:** ✅ Tested

**Motivation:**
The base groupId/artifactId ownership mapping was stored as an opaque regex-ish
string (`component_artifact_ids.artifact_id_pattern`). That had three problems:
(1) the inherited default surfaced in the UI as a raw catch-all `[\w-\.]+`,
opaque to users; (2) the per-range override of the ownership pattern was
import-only / not editable through v4; and (3) the legacy `EscrowConfigValidator`
cross-component rules #24 (exact token-pair sharing) and #25 (pattern containment)
— "at most one component matches any artifact" — could not be re-expressed
precisely while ownership was an opaque pattern, so v3 had silently dropped them.

**Description:**
Ownership is modelled explicitly. A component owns a LIST of mappings; each mapping
carries a comma-separated **group list**, an **ownership mode**, an optional
**version range** (base = ALL_VERSIONS), and — for EXPLICIT — a list of literal
**artifact tokens**:
- `EXPLICIT` — owns exactly the listed literal artifacts under its group(s).
- `ALL_EXCEPT_CLAIMED` — catch-all that yields to other components' EXPLICIT claims
  in intersecting ranges (maps the legacy `(?!X)` negative-lookahead). Single-group.
- `ALL` — owns every artifact under its group(s) unconditionally (the legacy/`main`
  default for a component with no explicit artifactId — preserved on migration).

Storage: `component_artifact_mappings (component_id, version_range, group_pattern,
artifact_id_mode, sort_order)` + `component_artifact_mapping_tokens (mapping_id,
artifact_pattern, sort_order)`, replacing `component_artifact_ids` and the
import-only `GROUP_ARTIFACT_PATTERN` ownership marker. A per-range override is just
mapping rows with a narrower range and REPLACES the base for that range
(most-specific wins). `sort_order=0` is the **primary** mapping; v1–v3 forward
output (`groupIdPattern`/`artifactIdPattern`, `/maven-artifacts`) renders the
primary mapping per range, while reverse `find-by-artifact` flattens ALL mappings so
ownership resolution stays complete for multi-mapping components. EXPLICIT tokens
are stored unescaped and dot-escaped only when rendered to a legacy regex-consumed
string; the v3 DB resolver matches EXPLICIT tokens by exact equality.

Cross-component uniqueness is decided deterministically from the stored modes (no
regex probing): for two mappings of different components sharing ≥1 group token in
intersecting EFFECTIVE-per-range windows — `EXPLICIT × EXPLICIT` conflicts iff their
token sets intersect; `EXPLICIT × ALL_EXCEPT_CLAIMED` never conflicts;
`ALL_EXCEPT_CLAIMED × ALL_EXCEPT_CLAIMED` conflicts; `ALL × anything` conflicts.

**Scope note (enforcement boundary):** the cross-component ownership matrix is
enforced on the v4 write path — `create` (unconditional) and `update` when
`artifactIds` is present (an unrelated field-override cannot change ownership and
must not re-trigger the check). It is deliberately NOT wired into the §6.0 migration
uniqueness pre-pass: production is overlap-free by construction under the legacy
single-match resolver, and the live API guards new writes. Migration instead
classifies each DSL artifactId into a mode strictly (`(?!`→ALL_EXCEPT; exact
catch-all set→ALL; literal enumeration→EXPLICIT; any other regex hard-fails — no
escape hatch); the real-production-DSL gate (`RealDslUniquenessAcceptanceTest`)
confirms 0 unclassifiable patterns and 0 §6.0 violations.

**Acceptance criteria:**
1. CREATE: two components with `EXPLICIT` mappings sharing a group and a token → 409
   (`UNIQUENESS_VIOLATION`, message names `groupId/artifactId ownership`).
2. CREATE: two `EXPLICIT` mappings sharing a group but disjoint tokens → 2xx.
3. CREATE: two `ALL` mappings on the same group → 409.
4. CREATE: `EXPLICIT` vs `ALL_EXCEPT_CLAIMED` on the same group → 2xx (catch-all yields).
5. CREATE: `ALL_EXCEPT_CLAIMED` with a comma group → 400 (single-group only).
6. PATCH with `artifactIds` introducing a colliding mapping → 409; a field-override
   PATCH (no `artifactIds`) never triggers the ownership check.
7. Migration classifies the real production DSL with 0 unclassifiable patterns;
   inherited/blank artifactId → `ALL`; `(?!X)` → `ALL_EXCEPT_CLAIMED`.
8. A migrated single-mapping component resolves behaviorally as before; a
   multi-mapping component's legacy forward render returns the `sort_order=0` mapping
   while `find-by-artifact` resolves an artifact owned by a non-primary mapping.

**Test method:** `OwnershipCollisionMatrixTest` (the 6-cell mode matrix +
intra-component disjointness + effective-per-range replacement),
`ArtifactOwnershipModeClassifierTest` (`plain catch-all forms classify as ALL`,
`null / blank artifactId classifies as ALL`, `negative-lookahead exclusion
classifies as ALL_EXCEPT_CLAIMED`, `literal single token and comma/pipe enumerations
classify as EXPLICIT`, `any other regex hard-fails — no escape hatch`),
`CrossComponentValidationTest` (`ownership_explicitVsExplicit_sameToken_conflict`,
`ownership_explicitVsExplicit_differentTokens_ok`, `ownership_allVsAll_conflict`,
`ownership_explicitVsAllExcept_ok`, `ownership_allExcept_commaGroup_badRequest`,
`ownership_patchArtifactIdsOnly_conflict`), and `RealDslUniquenessAcceptanceTest`
(prod-DSL gate). Forward/reverse rendering covered by
`DatabaseComponentRegistryResolverMavenArtifactsRangeTest`,
`DatabaseComponentRegistryResolverFindByArtifactTest`, `ComponentDetailMapperTest`,
`MIG047PerRangeGroupIdImportTest`.

### SYS-056: releaseManager / securityChampion list filters + summary fields

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The portal redesign adds a "my components" facet and a registry-health view that
need to slice the component list by the people responsible for it. componentOwner
is already filterable (SYS-035 / SYS-043), but release managers and security
champions are ordered multi-value child rows (component_release_managers /
component_security_champions) with no list filter and no list-summary projection,
so the portal could only get RM/SC by fetching each component detail individually.

**Description:**
Two additions, mirroring the owner filter (SYS-043) and the labels filter
(SYS-040):

1. `GET /components?releaseManager=<u>` and `GET /components?securityChampion=<u>`
   each filter the list to components on which the user appears in the respective
   ordered child collection. Both accept the standard multi-value wire shape (CSV
   `?releaseManager=a,b` or repeatable `?releaseManager=a&releaseManager=b`),
   normalised by the controller (split → trim → drop-blank → distinct →
   null-if-empty). Semantics is OR across the listed usernames (a component
   matches when ANY listed user is among its RMs / SCs) — implemented as a single
   JOIN through the child collection + `username IN (...)` + `query.distinct(true)`
   (mirrors the buildSystem single-join-IN shape; distinct because the join is to
   a collection). The two filters AND with each other and with every other filter.

2. The list summary (`ComponentSummaryResponse`) additionally emits
   `releaseManagers: List<String>` and `securityChampions: List<String>` (ordered
   by sort_order, first = primary), populated from the entity username helpers.
   Like the existing `labels` list field they are emitted-empty-not-null. The
   collections are `@BatchSize`-batched (BATCH_FETCH_SIZE = 1000) so the paged list
   path loads them without an N+1 (≤ page-size proxies pending → one IN per role).

**Acceptance criteria:**
1. `?releaseManager=u` returns exactly the components having `u` as a release
   manager and excludes components where `u` is not an RM (incl. where `u` is only
   an SC or only the owner).
2. `?securityChampion=u` behaves symmetrically for the security-champion collection.
3. CSV / repeatable multi-value input is OR across values; blanks and duplicates
   are normalised away (parity with SYS-043 owner).
4. `?releaseManager=<unknown>` returns an empty page; absent param = no filter.
5. The list summary carries `releaseManagers` / `securityChampions` arrays in
   sort order, `[]` (not null) when none.

**Test method:** `ListComponentsPeopleFilterTest` —
`SYS-056 releaseManager filter returns only components where user is an RM`,
`SYS-056 securityChampion filter returns only components where user is an SC`,
`SYS-056 releaseManager CSV is OR across values`,
`SYS-056 unknown releaseManager returns empty`,
`SYS-056 summary emits releaseManagers and securityChampions arrays`.

---

### SYS-057: Registry health statistics endpoint (counts + people)

**Priority:** High
**Test layer:** integration-test
**Status:** ✅ Tested

**Motivation:**
The portal redesign adds an admin "Registry Health" page that needs registry-wide
aggregate numbers (how many components, how many active, and the distribution
across owners / release managers / security champions) to surface concentration
risk (e.g. one owner on 200 components). Computing these portal-side would mean
downloading the whole list; CRS owns the data and can aggregate in SQL.

**Description:**
`GET /rest/api/4/health/statistics` (new `HealthControllerV4`, same package and
`@ConditionalOnDatabaseEnabled` level as `ComponentControllerV4`) returns:

- `totalComponents: Long` — all non-FAKE-aggregator component rows.
- `activeComponents: Long` — the non-archived subset.
- `componentsByOwner: Map<String, Long>` — count of ACTIVE (non-archived)
  components per `component_owner` (scalar column GROUP BY; null/blank owners excluded).
- `componentsByReleaseManager: Map<String, Long>` — count of ACTIVE components per
  release-manager username (GROUP BY over the component_release_managers child table).
- `componentsBySecurityChampion: Map<String, Long>` — count of ACTIVE components per
  security-champion username (GROUP BY over component_security_champions).

The three people maps count ACTIVE (non-archived) components only, so a person's
breakdown reflects their live workload (consistent with `activeComponents`); a person
whose only components are archived does not appear in any map. `totalComponents` keeps
the full count and `activeComponents` the non-archived count.

Every figure is computed with a repository aggregation query (COUNT / GROUP BY),
NOT by loading entities into memory. The endpoint is **counts + people only**:
problem/validation aggregation is owned by the portal backend, not CRS, and is
deliberately absent (the response is shaped so a future problem dimension can be
added by a different owner without colliding here).

Gated by `ACCESS_COMPONENTS` — the same read permission every other v4 list/read
endpoint uses. "Admin only" is a portal nav-visibility concern; the figures expose
only aggregates over data already readable via the list, so no new CRS permission
is invented.

**Acceptance criteria:**
1. `totalComponents` / `activeComponents` reflect the seeded set (active =
   non-archived).
2. `componentsByOwner` / `componentsByReleaseManager` / `componentsBySecurityChampion`
   carry the correct per-username counts (a user on N active components → N); a user who is
   both RM and SC is counted once in each map.
3. The counts are produced by GROUP BY aggregation, not entity hydration.
4. A `viewerJwt` (ACCESS_COMPONENTS) gets `200`; an unauthenticated request gets `401`.
5. The response carries no problem/validation fields.
6. The three people maps exclude ARCHIVED components: a person's count reflects active
   components only, and a person whose only components are archived is absent from the map.

**Test method:** `HealthControllerV4Test` —
`SYS-057 statistics returns total and active component counts`,
`SYS-057 statistics groups components by owner RM and SC`,
`SYS-057 statistics people breakdowns count active components only`,
`SYS-057 statistics is ACCESS_COMPONENTS gated`.

### SYS-059: Live version-format preview endpoint

**Priority:** High
**Test layer:** unit + integration-test
**Status:** ✅ Tested

**Motivation:**
The Portal component editor shows a "Version Preview" of the coordinates a version
would resolve to. Previously it either rendered client-side (a hand-maintained
ladder that drifts from the server's real formatter) or called the saved-config
`detailed-version` endpoint (which reflects the *persisted* config, not the
*unsaved* edits). For customers with custom version prefixes / zero-padding the
client ladder cannot faithfully reproduce the server's rendering. A stateless
server endpoint that renders from the **unsaved** editor config makes the preview
live and byte-accurate without a save.

**Description:**
`POST /rest/api/4/versions/preview` (new `VersionsControllerV4`, NOT
`@ConditionalOnDatabaseEnabled` — rendering only needs the always-present
`VersionNames` / formatter beans, so it works in git and DB modes). The request
carries an input `version`, a `base` block of effective Jira formats, and a list
of per-range `overrides`; there is no persistence and no component lookup.

Server logic (`VersionPreviewService`):
1. Resolve the range: the first override whose `versionRange` contains the input
   version wins; the `base` applies when none matches.
2. Materialise the effective `ComponentVersionFormat` = base overlaid with the
   matched override's present (non-null) fields. `minorVersionFormat` /
   `releaseVersionFormat` default to `$major` / `$major.$minor` when blank, and
   `lineVersionFormat` / `buildVersionFormat` mirror `minorVersionFormat` /
   `releaseVersionFormat` when blank — matching `EntityMappers.buildJiraComponent`
   (the persisted DB path); the editor contract documents the same fallbacks.
3. Canonicalise the input via `JiraComponentVersionFormatter.normalizeVersion(...,
   strict=false, hotfixEnabled)` — exactly as `getJiraComponentVersion` does: pick
   the format the version matches, re-render it, and drive ALL coordinates off that
   clean version. A version matching no format → `null` → `NotFoundException` (404),
   mirroring the resolver's not-found.
4. Build a `JiraComponentVersion` from those formats + the clean version and hand
   it to the existing `Mapper<JiraComponentVersion, DetailedComponentVersion>` — the
   SAME render seam the persisted `detailed-version` uses, so output is
   byte-for-byte identical for the same effective config + version.

Field naming mirrors the v4 write contract (`minorVersionFormat` is the
resolver's `majorVersionFormat`). `buildSystem` is deliberately absent: padding /
custom-variable expansion is driven purely by the format templates + `VersionNames`.
Hotfix eligibility is caller-supplied via `hotfixEnabled` (VCS-branch-derived in the
persisted path, NOT inferred from the presence of a hotfix format); a hotfix
coordinate renders only when `hotfixEnabled` is true and a hotfix format resolves.
Custom-component behaviour (`versionPrefix` + `versionFormat`) renders the wrapped
value on `jiraVersion` while `version` stays the raw template render — exactly the
dichotomy `detailed-version` already produces. Authenticated-only via the
`rest/api/4` catch-all; no finer permission since no persisted data is touched.

**Acceptance criteria:**
1. Base formats render the six coordinates for the input version, matching
   `detailed-version` for the same effective config.
2. A version inside an override range renders that range's format; outside → base.
3. Custom `versionPrefix`/`versionFormat` renders the wrapped `jiraVersion`;
   `version` stays unwrapped. Zero-padding is driven purely by the format template.
4. A hotfix coordinate renders only when `hotfixEnabled` is true and a hotfix format
   resolves; a hotfix format alone (with `hotfixEnabled` false) renders none.
5. Blank / non-numeric `version`, a malformed override range, or a `versionPrefix`
   without `versionFormat` → `400`; a version matching none of the supplied formats
   → `404`.
6. An authenticated caller gets `200`; an unauthenticated request gets `401`.
7. Preview output equals `detailed-version` for a real seeded component (parity guard).

**Test method:** `VersionPreviewServiceImplTest` —
`SYS-059 base render`, `SYS-059 override range selection`,
`SYS-059 custom prefix render`, `SYS-059 padding is template-driven`,
`SYS-059 hotfix coordinate`, `SYS-059 unmatched version is not found`,
`SYS-059 invalid version rejected`,
`SYS-059 malformed override range rejected`,
`SYS-059 prefix without wrapper format rejected`; `VersionsControllerV4Test` —
`SYS-059 authenticated preview returns rendered coordinates`,
`SYS-059 unauthenticated preview is 401`, `SYS-059 invalid version is 400`,
`SYS-059 preview matches detailed-version for a real component`.

### SYS-060: Operational service-event journal

Migration/resync job runs and pod redeploys previously lived only in an in-memory
`AtomicReference` (single-pod, lost on restart) + SLF4J logs, so operators had no
persistent history. SYS-060 adds an append-only `service_event` table
(`V4__add_service_event.sql`; also auto-created by Hibernate `ddl-auto` in the
flyway-disabled envs) recording:

- **STARTUP** — one terminal row per pod boot, carrying `service_version`
  (`ServiceStartupListener` on `ApplicationReadyEvent`), giving a redeploy history.
- **MIGRATION_COMPONENTS / MIGRATION_HISTORY / TEAMCITY_RESYNC** — one row per run,
  created RUNNING at the top of the work runnable and transitioned in place to
  COMPLETED/FAILED (matched by the job id in `correlation_id`).

`ServiceEventRecorder` owns the writes. Each runs in its own `REQUIRES_NEW`
transaction wrapped in a swallow-and-log so a journal failure never rolls back or
crashes the job/boot it observes (mirrors `GitHistoryCommitWriter`). `recordFinish`
falls back to inserting a terminal row if no RUNNING row exists.

**Failure reporting (hard requirement):** every job-failure path writes a FAILED row
with the error in `detail` — the `catch` branch of each run, AND the executor-reject
path (`startAsync` catches the rejection, records a standalone terminal FAILED, and
rethrows). A run interrupted by a pod restart (RUNNING row with no live job) is
reconciled to `FAILED("interrupted by restart")` on the next startup. Single-pod only
(prod `replicas: 1`) — the reconcile flips all crs RUNNING rows.

`GET /rest/api/4/admin/service-events` returns the paginated journal (newest first;
optional `eventType`/`source`/`status`/`from`/`to` filters), IMPORT_DATA-gated like
`AdminControllerV4`. A `@Scheduled` daily prune deletes rows older than
`components-registry.service-events.retention-days` (default 90) — scheduled, not
startup-only, because a long-lived pod would otherwise never prune.

**Acceptance criteria:**
1. A pod start writes a terminal STARTUP row with the build version.
2. A TeamCity resync (and components/history migration) writes a RUNNING row that
   transitions to COMPLETED with the result counters in `detail` on success.
3. A job that throws writes a single FAILED row (same `correlation_id`) with the
   error message; a partial TC run (result carries per-component errors) COMPLETEs
   with a "completed with errors" summary.
4. An executor-rejected submission (no runnable ran) still writes a terminal FAILED row.
5. On startup, any leftover RUNNING row of source `crs` is flipped to FAILED
   ("interrupted by restart").
6. A recorder write that throws is swallowed (logged) and does not propagate to the job.
7. `GET /admin/service-events` returns rows newest-first, honours the filters, and is
   403 for a caller without IMPORT_DATA.

**Test method:** `ServiceEventRecorderImplTest` —
`SYS-060 recordStart then recordFinish transitions the running row`,
`SYS-060 recordFinish without a running row inserts a terminal row`,
`SYS-060 reconcileOrphanedRunning flips running rows to failed`,
`SYS-060 prune deletes rows before the cutoff`,
`SYS-060 a write failure is swallowed`;
`TeamcitySyncJobServiceImplTest` — `SYS-060 completed run records COMPLETED`,
`SYS-060 failed run records FAILED`, `SYS-060 rejected submission records FAILED`;
`ServiceStartupListenerTest` — `SYS-060 startup reconciles and records STARTUP`;
`ServiceEventControllerV4Test` — `SYS-060 lists newest-first with filters`,
`SYS-060 read requires IMPORT_DATA`.

### SYS-061: Portal service-event ingest (shared-secret)

The "validation of components" sweep and portal redeploys are portal-owned and not
visible to CRS. SYS-061 lets the portal BFF report them into the shared journal via
`POST /rest/api/4/admin/service-events` so the Admin "Events" tab shows both services
on one timeline. Portal events arrive already-terminal (COMPLETED/FAILED) with
caller-supplied timestamps; the body carries `eventType`/`status`/`source` (parsed
leniently against the server enums → 400 on unknown values).

Auth is a shared-secret `X-Service-Event-Token` header, not a JWT — the portal BFF
calls CRS tokenless (same internal-network pattern as the permitAll `/migration-status`
probe). The POST is method-scoped permitAll at the filter chain (above the
`/rest/api/4/**` authenticated catch-all) so the sibling GET read stays JWT +
IMPORT_DATA gated; the secret is verified in the controller (a `@PreAuthorize` cannot
read a header), constant-time (`MessageDigest.isEqual`), **fail-closed**: a blank/unset
configured token rejects every call (403), so a misconfiguration never opens ingest to
the network. A leaked token only lets an attacker forge journal rows (no component data
is mutated); a stronger service-account/OIDC/mTLS scheme is a post-cutover follow-up.

**Acceptance criteria:**
1. A POST with the correct token and a valid body records a terminal portal-sourced row.
2. A POST with a wrong/missing token → 403; with the configured token blank/unset →
   403 (fail-closed) even if the header is present.
3. An unknown `eventType`/`status`/`source` → 400.
4. The GET read side is unaffected (still JWT + IMPORT_DATA).

**Test method:** `ServiceEventIngestControllerV4Test` —
`SYS-061 valid token and body records the event`,
`SYS-061 wrong token is 403`, `SYS-061 blank configured token is fail-closed 403`,
`SYS-061 unknown eventType is 400`.

### SYS-062: User feedback / report-a-problem

A single "feedback" surface in the portal lets any authenticated user file a report
(`type` = BUG / IDEA / QUESTION) with a free-form message and up to a few optional
screenshots. Reports have their own triage lifecycle (NEW → IN_PROGRESS → RESOLVED),
distinct from `audit_log` (entity changes) and `service_event` (job runs). Admins
(IMPORT_DATA) browse, filter, view screenshots, and advance status; see ADR-019 for
the storage/transport/security decisions.

Screenshots are stored inline as `bytea` in `feedback_attachment` (one row per file).
The portal transports them base64-in-JSON; the service decodes and validates each by
**magic bytes** (PNG/JPEG only — never the client's `Content-Type`), size, and count
before storing, and persists a **server-normalized** MIME that is later echoed on the
attachment-bytes response with `X-Content-Type-Options: nosniff` and an `inline`
Content-Disposition. Body size is capped in two rubrics: a portal-gateway limit
(primary) and a CRS ingress guard (second line, `413` on over-cap, covering both
`Content-Length` and chunked bodies). RESOLVED reports are pruned by a scheduled
retention job (`updated_at` older than the window; `retention-days <= 0` disables).

Submit is authenticated-only (`POST /rest/api/4/feedback`, filter-chain
`authenticated()` — anonymous → 401; submitter taken from the JWT, never the body).
Admin reads/triage are IMPORT_DATA-gated (`/rest/api/4/admin/feedback**`) —
`ACCESS_AUDIT` is deliberately NOT used because viewers/editors hold it.

**Acceptance criteria:**
1. Anonymous submit → 401; authenticated submit → 201 with `submittedBy` from the JWT.
2. A PNG/JPEG screenshot is accepted; a non-image, oversized, or over-count attachment
   → 400; the stored/served MIME is server-derived, not the client's claim.
3. An over-cap request body → 413.
4. Admin list/detail/status/attachment reads require IMPORT_DATA (viewer/editor → 403);
   an attachment id under the wrong feedback id → 404.
5. A status change stamps `updated_by` from the JWT.
6. `GET /rest/api/4/admin/feedback/open-count` returns the count of OPEN (not RESOLVED)
   reports (IMPORT_DATA-gated), for the portal admin-header badge.

**Test method:** `FeedbackControllerV4Test` —
`SYS-062 submit requires authentication`, `SYS-062 submit stores submitter from jwt`,
`SYS-062 submit stores a valid png attachment`,
`SYS-062 submit rejects non image attachment`,
`SYS-062 submit rejects too many attachments`,
`SYS-062 admin list is import gated`, `SYS-062 admin fetches attachment bytes`,
`SYS-062 attachment scoped to its feedback`, `SYS-062 admin updates status`,
`SYS-062 admin open count`;
`FeedbackRequestSizeFilterTest` — `SYS-062 body over cap is rejected`.

### SYS-063: TeamCity project reconciliation (version lines)

**Priority:** Medium
**Test layer:** unit-test
**Status:** ✅ Tested

**Motivation:**
The TeamCity sync links each component to the TeamCity project(s) that claim it via the
`COMPONENT_NAME` parameter. A component may legitimately have several projects — one per
release line — so a single flat association is insufficient. This requirement covers the
reconciliation *behavior*; SYS-051 separately covers the decision NOT to write audit rows.

**Description:**
`TeamcitySyncService` reconciles per component against a batched TeamCity scan:
1. `COMPONENT_NAME` (and `PROJECT_VERSION`) values are resolved recursively through TeamCity
   `%param%` references; an unresolved reference (missing key / cycle) yields no value.
2. `PROJECT_VERSION` is read from the project, else deterministically from its non-paused
   build types (distinct valid values in `id` order; conflicting values → ambiguous → none),
   and must match a numeric version format (`1.2`, `2.2.4`, `03.64.53-2`) or it is treated as
   absent.
3. If any candidate declares a `PROJECT_VERSION`, null-version ("line-less") candidates are
   dropped (nulls survive only when every candidate is null).
4. Candidates are grouped by `PROJECT_VERSION`; each line resolves to one project — a lone
   candidate wins, ties prefer a non-paused CDRelease build then the lexicographically smallest
   id, and a tie with no release build leaves the line unresolved.
5. Archived projects and projects whose build configs are all paused are excluded.

Persistence is the transitional `version_line` → `teamcity_project` model: one `version_line`
row per kept line (`version` is sync-owned, nullable, a reconciliation discriminator — see
`VersionLineEntity`), pointing at a deduplicated `teamcity_project`. Reconciliation is
idempotent (writes only when the (projectId, version) set changes). Counters are reported per
component (`updated`/`unchanged`/`skipped_no_match`/`skipped_ambiguous`/`ambiguous_auto_resolved`);
`dropped_lines` separately surfaces lines dropped from otherwise-linked components. A v4 PATCH
preserves the sync-owned `version` for project ids it retains.

**Acceptance criteria:**
1. Multiple `PROJECT_VERSION` lines → one persisted project per line.
2. Null-version candidates are discarded when any versioned candidate exists; all-null keeps the default line.
3. Within a line, the CDRelease-then-smallest-id tie-break selects deterministically; no release build → unresolved.
4. The build-type version fallback is deterministic and treats conflicting values as ambiguous.
5. Unresolved `%param%` references and non-version-format values yield no version.
6. A v4 PATCH that re-submits an existing project id does not wipe its sync-owned `version`.
7. `dropped_lines` counts lines dropped from linked components without inflating `skipped_*`.

**Test method:** `TeamcitySyncServiceTest` (multi-line, null-discard, tie-break, `dropped_lines`
cases) and `ExternalTcProjectFetcherTest` (reference resolution, version-format, deterministic
build-type fallback, archived/all-paused exclusion); v4 PATCH preservation in
`ComponentManagementServiceImpl` write-path tests.

### SYS-064: component-validation — ATTACHED_TO_BUILD_TEMPLATE

New pure-Kotlin module `component-validation` (see
[`docs/teamcity-validation-design.md`](../teamcity-validation-design.md) and
[`-implementation-brief.md`](../teamcity-validation-implementation-brief.md)) validates
TeamCity projects: input in (`TeamcityProject`), `List<ValidationResult>` out — no Spring, no
DB, no TeamCity-client dependency, no IO. `ATTACHED_TO_BUILD_TEMPLATE` is the first of its five
checks: is any build configuration attached to a build template (`CDGradleBuild` /
`CDJavaMavenBuild` in the real deployment; the module itself takes a `TemplateCatalog` and knows
no concrete ids)?

**Description:**
`AttachedToBuildTemplateValidator` reports `OK` when exactly one configuration is attached
(`BuildConfigurationResolver.attachedToBuildTemplate(project).size == 1`), `WARNING` when more
than one is attached (ambiguous — which one is authoritative?), `WARNING` when none is — a
finding about the project's own configuration, not a failure of validation itself, so it stays at
the same severity as every other content check (`ERROR` is reserved for the validation process
itself failing to run, see decision D6). Always applicable (never `NOT_APPLICABLE`).

**Acceptance criteria:**
1. A project with exactly one configuration attached to a build template → `OK`.
2. A project with more than one configuration attached to a build template → `WARNING`.
3. A project with configurations, none attached to a build template → `WARNING`.
4. A project with no build configurations at all → `WARNING`.

**Test method:** `AttachedToBuildTemplateValidatorTest` —
`SYS-064 OK for a single attached config`, `SYS-064 WARNING for multiple attached configs`,
`SYS-064 WARNING when nothing attached`, `SYS-064 WARNING for empty project`.

### SYS-065: component-validation — OVERRIDES_DEFAULT_BUILD_STEP

**Description:**
`OverridesDefaultBuildStepValidator` reports `NOT_APPLICABLE` when no configuration is attached
to a build template. Otherwise, for each attached configuration's default build step
(`BuildStepSelector.defaultBuildStep`, id `X` per `TemplateCatalog.defaultBuildStepId`): any
`OVERRIDDEN` → `WARNING` (message names the configuration(s)); all `INHERITED` → `OK`.

**Acceptance criteria:**
1. No attached configuration → `NOT_APPLICABLE`.
2. Attached configuration, default step `INHERITED` → `OK`.
3. Attached configuration, default step `OVERRIDDEN` → `WARNING`.

**Test method:** `OverridesDefaultBuildStepValidatorTest` —
`SYS-065 NOT_APPLICABLE when nothing attached`, `SYS-065 OK when default step inherited`,
`SYS-065 WARNING when default step overridden`.

### SYS-066: component-validation — HAS_CUSTOM_BUILD_STEP

**Description:**
`CustomBuildStepValidator` answers a single question: "is there any overriding custom build step
at all" — it doesn't matter whether that step uses Java or Maven, so `HAS_CUSTOM_JAVA_BUILD_STEP`
and `HAS_CUSTOM_MAVEN_BUILD_STEP` were merged into one `HAS_CUSTOM_BUILD_STEP` (see decision log
§5 decision 24). It gathers every uninherited build step across every build configuration —
attached to a build template or not — resolves each one's tool versions via
`BuildStepToolVersionResolver`, and reports `WARNING` if any uninherited step resolves any tool
version at all (Java or Maven), `OK` otherwise. Always applicable.

**Acceptance criteria:**
1. No uninherited step at all → `OK`.
2. An uninherited step exists but resolves no tool version → `OK`.
3. A non-template configuration has an uninherited step resolving a Java version → `WARNING`.
4. A non-template configuration has an uninherited step resolving a Maven version → `WARNING`
   (both tools count — the question is "is there a custom step", not "which tool").
5. An attached configuration's overridden default step resolves a version → `WARNING`.

**Test method:** `CustomBuildStepValidatorTest` —
`SYS-066 OK when nothing custom`, `SYS-066 OK when custom step resolves nothing`,
`SYS-066 WARNING for non-template custom step with a java version`,
`SYS-066 WARNING for non-template custom step with a maven version`,
`SYS-066 WARNING for overridden default step`.

### SYS-067: component-validation — USES_OLD_JAVA_VERSION

**Description:**
`OldJavaVersionValidator` is a single pass owning "where do I read the version" per step, so
nothing is double-counted or missed. It delegates to `BuildStepToolVersionResolver` (see
SYS-071) uniformly for every step — every uninherited (`OVERRIDDEN`) build step across every
build configuration (attached to a template or not) plus each attached configuration's default
build step regardless of its own `inherited` flag — since the resolver reads each step's own
parameters directly, there is no separate "inherited reads config-level, overridden reads
step-level" branch anymore. `WARNING` if any resolved `JavaVersion.isEight` — this is a finding
about the project's configuration, not a failure of validation itself, so it stays a warning
rather than `ERROR` (which is reserved for when the validation process itself couldn't run
cleanly, e.g. a validator throwing — see decision D6); `OK` if versions resolved but none is 1.8;
`NOT_APPLICABLE` if nothing Java-relevant was inspected (tracked via
`BuildStepToolVersionResolver.supports(type)`, not a fixed runner-type set owned by the
validator). Resolved decision **D7**: an unresolved (`null`) version is ignored, not flagged.

**Acceptance criteria:**
1. Nothing Java to inspect → `NOT_APPLICABLE`.
2. Inherited default step resolving to Java 1.8 → `WARNING`.
3. Inherited default step resolving to a modern Java version → `OK`.
4. Overridden default step resolving to Java 1.8 → `WARNING`.
5. Custom (uninherited) step on any configuration resolving to Java 1.8 → `WARNING`.
6. A parameter present but not parseable as a version → ignored, not flagged (`OK`, not
   `WARNING`), since a default step was still inspected.

**Test method:** `OldJavaVersionValidatorTest` — `SYS-067 NOT_APPLICABLE when nothing to inspect`,
`SYS-067 WARNING for inherited default step on Java 8`,
`SYS-067 OK for inherited default step on Java 21`,
`SYS-067 WARNING for overridden default step on Java 8`,
`SYS-067 WARNING for custom step on Java 8`,
`SYS-067 OK when version cannot be resolved`.

### SYS-068: component-validation — ValidatorSuite error isolation (D6)

**Description:**
Resolved decision **D6** (implementer's discretion per the implementation brief): a validator
that throws must not sink the whole suite. `ValidatorSuite.validate` catches a `RuntimeException`
from an individual validator and turns it into a `Status.ERROR` result (type = the failing
validator's type, message = the exception detail) instead of propagating, so the remaining
validators' results are still returned.

**Acceptance criteria:**
1. One throwing validator among several → its slot becomes `Status.ERROR`; every other
   validator's result is unaffected.
2. No throwing validator → every result passes through unchanged.

**Test method:** `ValidatorSuiteTest` — `SYS-068 throwing validator is isolated as an ERROR result`,
`SYS-068 all-ok suite returns every result as-is`.

### SYS-069: component-validation — JavaVersion.isEight and version normalization

**Description:**
`JavaVersion.isEight` is a literal test: `raw.trim() == "1.8" || raw.trim() == "8"` (per the
implementation brief — no fuzzy matching at that layer). The burden of normalizing real-world
values to that exact major-version form falls on `JavaVersionResolver` (decision **D1** — see
TD-016): it extracts the major (or `1.<major>`) version from clean strings (`"11.0.2"` → `"11"`),
from build-suffixed strings (`"1.8.0_392"` → `"1.8"`, `"8u392"` → `"8"`), and from
marker-embedded identifiers (`"JDK_21_0_x64"` → `"21"`, `"JDK_ZULU_17_x64"` → `"17"`,
`"/opt/java/openjdk-11"` → `"11"`).

**Acceptance criteria:**
1. `JavaVersion("1.8").isEight` and `JavaVersion("8").isEight` are `true`.
2. `JavaVersion("11")`, `("17")`, `("21")`, `("1.7")`, `("80")`, and a full build string
   `("1.8.0_392")` all have `isEight == false` (normalization is the resolver's job, not
   `isEight`'s).
3. `JavaVersionResolver.resolve(...)` correctly extracts the major version from every real-world
   shape listed above, and returns `null` for a blank or unrecognised value.

**Test method:** `JavaVersionTest` — `SYS-069 isEight true for 1_8 and 8`,
`SYS-069 isEight false for other versions`; `JavaVersionResolverTest` —
`resolve extracts version` (parameterized), `resolve returns null when nothing resembles a version`.

### SYS-070: component-validation — TeamCityValidators suite, end to end

**Description:**
`TeamCityValidators` wires the six validators together over one shared `BuildConfigurationResolver`
/ `BuildStepResolver` pair and returns exactly six `ValidationResult`s per `validate(project)`
call, matching what each validator would independently produce.

**Acceptance criteria:**
1. A well-behaved project (attached, inherited default step, modern Java, no custom steps) →
   all six checks `OK`.
2. An unattached project with an old-Java custom command-line step → `ATTACHED_TO_BUILD_TEMPLATE`
   `WARNING`, `OVERRIDES_DEFAULT_BUILD_STEP` `NOT_APPLICABLE`, `HAS_CUSTOM_BUILD_STEP` `WARNING`,
   `USES_OLD_JAVA_VERSION` `WARNING`, both `MULTIPLE_*_VERSIONS` `OK` (only one distinct version
   of either tool resolves).

**Test method:** `TeamCityValidatorsTest` — `SYS-070 well-behaved project resolves all six checks`,
`SYS-070 unattached project with custom java 8 step`.

### SYS-071: component-validation — BuildStepToolVersionResolver (per-step-type tool-version dispatch)

**Description:**
A step-scoped abstraction: given a single `BuildStep`, `BuildStepToolVersionResolver.resolve(step)`
returns the `Set<ToolVersion>` (Java and/or Maven, for now) that step uses. Dispatch is by
`StepType` (`DefaultBuildStepToolVersionResolver`): `MAVEN` → reads `maven.path` (Maven version)
and `target.jdk.home` (Java version); `GRADLE` → reads only `target.jdk.home`; `COMMAND_LINE` and
`IN_CONTAINER` (the latter an unconfirmed assumption — an in-container step is assumed to run a
script the same way a command-line step does; see TD-016) → read `script.content`, split it on
whitespace, and try *both* the Maven and the Java value-resolver on each (reference-resolved)
token, since a bare command line gives no other signal about which tool a token belongs to.
Every parameter read first goes through `ParameterReferenceResolver`, which recursively
substitutes TeamCity `%paramName%` references within the same `Parameters` bag until no
reference remains, returning `null` on a missing reference or a reference cycle (mirroring real
TeamCity `%param%` semantics). The "value → version" step is a separate, per-tool abstraction
(`ValueVersionResolver<V>`): `JavaVersionResolver` and `MavenVersionResolver` each derive their
tool's version from an already reference-resolved raw string.

**Acceptance criteria:**
1. A Maven step with both `maven.path` and `target.jdk.home` set → both a `MavenVersion` and a
   `JavaVersion` are resolved.
2. A Gradle step → only a `JavaVersion` is ever resolved, never a `MavenVersion`.
3. A command-line step's `script.content` → split into tokens; each token is tried against both
   resolvers; a token that is itself a `%param%` reference is resolved first.
4. `IN_CONTAINER` steps are dispatched through the same logic as `COMMAND_LINE` steps.
5. An unsupported `StepType` (e.g. `OTHER`) → `resolve` returns an empty set; `supports` returns
   `false`.
6. `ParameterReferenceResolver` follows single- and multi-hop `%param%` chains, resolves multiple
   distinct (or repeated) references within one value, and returns `null` on a missing reference
   or a direct/indirect cycle — without false-flagging a legitimately repeated reference as a
   cycle.

**Test method:** `BuildStepToolVersionResolverTest` (Maven/Gradle/command-line/dispatcher cases),
`ParameterReferenceResolverTest` (single-hop, multi-hop, missing, direct cycle, indirect cycle,
repeated-reference, embedded-value cases), `JavaVersionResolverTest`, `MavenVersionResolverTest`.

### SYS-072: component-validation — MULTIPLE_JAVA_VERSIONS

**Description:**
`MultipleJavaVersionValidator` reuses the same step-gathering logic as `OldJavaVersionValidator`
(every uninherited build step across every build configuration, plus each attached
configuration's default build step regardless of its own `inherited` flag) and resolves a Java
version per step via `BuildStepToolVersionResolver`. `WARNING` if more than one distinct
`JavaVersion` is found across those steps; `OK` if zero or one distinct version is found;
`NOT_APPLICABLE` if nothing was inspectable.

**Acceptance criteria:**
1. Nothing Java to inspect → `NOT_APPLICABLE`.
2. Exactly one distinct Java version found (whether from one step or repeated across several
   steps) → `OK`.
3. No Java version resolved from any inspected step → `OK`.
4. More than one distinct Java version found across the inspected steps → `WARNING`.

**Test method:** `MultipleJavaVersionValidatorTest` —
`SYS-072 NOT_APPLICABLE when nothing to inspect`, `SYS-072 OK for a single distinct Java version`,
`SYS-072 OK when no Java version resolves`, `SYS-072 WARNING for multiple distinct Java versions`.

### SYS-073: component-validation — MULTIPLE_MAVEN_VERSIONS

**Description:**
`MultipleMavenVersionValidator` mirrors `MultipleJavaVersionValidator`, using the same
step-gathering logic (every uninherited build step across every build configuration, plus each
attached configuration's default build step regardless of its own `inherited` flag) and resolving
a Maven version per step via `BuildStepToolVersionResolver`. `WARNING` if more than one distinct
`MavenVersion` is found across those steps; `OK` if zero or one distinct version is found;
`NOT_APPLICABLE` if nothing was inspectable.

**Acceptance criteria:**
1. Nothing Maven to inspect → `NOT_APPLICABLE`.
2. Exactly one distinct Maven version found (whether from one step or repeated across several
   steps) → `OK`.
3. No Maven version resolved from any inspected step → `OK`.
4. More than one distinct Maven version found across the inspected steps → `WARNING`.

**Test method:** `MultipleMavenVersionValidatorTest` —
`SYS-073 NOT_APPLICABLE when nothing to inspect`, `SYS-073 OK for a single distinct Maven version`,
`SYS-073 OK when no Maven version resolves`, `SYS-073 WARNING for multiple distinct Maven versions`.

### SYS-074: Component owner's manager may edit the component

**Priority:** High
**Test layer:** unit + integration-test
**Status:** ✅ Tested

`PermissionEvaluator.canEditComponent`'s ownership predicate (§6.3: `componentOwner ||
releaseManager || securityChampion || EDIT_ANY_COMPONENT`) gets a fourth, derived
condition: the current user is the **manager** of the component's `componentOwner`,
as resolved by `EmployeeDirectoryService.getManager` (backed by the employee-service
`getManager` API). This lets a manager cover for an absent or departed report without
needing the admin-only `EDIT_ANY_COMPONENT`.

The manager check runs last, after the cheaper DB-only owner/RM/SC projections — it is
the only condition of the four that makes a network call. Any failure to resolve a
manager (the owner has no manager, the owner is not found, or employee-service is
unavailable/erroring) makes `getManager` return `null`, which denies the grant — a
directory failure can only deny edit access here, never grant it (fail-closed, the
opposite direction from the fail-open convention used by the read-only
active-employee checks elsewhere in `EmployeeDirectoryService`).

Unlike the admin bypass (an open-ended Keycloak realm-role, not per-component data),
the manager resolves to one concrete person for this component, so — unlike an
earlier draft of this requirement — it IS enumerated: `GET /{idOrName}/editors` adds
a `manager` field (null when the owner has none / is unresolvable / employee-service
is unavailable) alongside `componentOwner`/`releaseManager`/`securityChampion`, so the
Portal's "who can edit" surface reflects this grant. `getManager` backs two call
sites per request in the worst case (the `@PreAuthorize` check and this projection),
so a resolved answer — including a confirmed "no manager" — is cached for 2 minutes
per owner in `EmployeeDirectoryService`; lookup failures are deliberately excluded
from the cache so a transient employee-service outage cannot extend into a
multi-minute false denial after the service recovers. `getEditors`' DB read (the
entity + its LAZY release-manager/security-champion collections) runs in its own
short-lived transaction via an explicit `TransactionTemplate`, which closes BEFORE
the manager lookup — so a slow/degraded employee-service call never holds a pooled
DB connection for its duration.

**Acceptance criteria:**
1. The manager of a component's `componentOwner` (per employee-service `getManager`)
   may PATCH the component and its field-overrides, same as an explicit owner/RM/SC.
2. A user who is not the owner's manager gets 403.
3. When employee-service reports no manager for the owner (`ManagerDTO.manager ==
   null`), the grant is denied.
4. `GET /{idOrName}/editors` includes a `manager` field resolved the same way; it is
   null when the owner has no manager.

**Test method:** `PermissionEvaluatorTest` —
`SYS-064 manager of componentOwner allowed`,
`SYS-064 non-manager of componentOwner denied`,
`SYS-064 owner with no manager denied`;
`ComponentOwnershipEditSecurityTest` —
`SYS-064 manager of componentOwner can PATCH the component`,
`SYS-064 non-manager of componentOwner gets 403 on PATCH`,
`SYS-064 editors includes owner manager`,
`SYS-064 editors omits manager when owner has none`.
