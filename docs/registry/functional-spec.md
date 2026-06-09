# Functional Specification: Components Registry DB Migration

## Status
**Living document** | Last updated: 2026-04-29 (was Draft 2026-03-08)

---

## 1. Component Management

### 1.1 List Components
- **Input**: Optional filters, ANDed when combined.
  - **Main:** `search` (case-insensitive LIKE on name/displayName), `system` (multi-value, OR), `owner` (multi-value, OR on `componentOwner`, `SYS-035`), `buildSystem` (multi-value, OR on the BASE row), `labels` (multi-value, AND), `archived`, `productType`.
  - **Extended — multi-value (OR, exact `IN`; back the Portal "extended search" multi-select dropdowns, `SYS-046`):** `clientCode`, `jiraProjectKey` (BASE row), `parentComponentName` (the parent's component key — children of any listed parent), `groupKey` (the owning group). CSV or repeatable params, normalised like the Main multi-value filters; the BASE-row join uses `distinct`. (These four were substring/exact single-value before `SYS-046`.)
  - **Extended — single-value:** `solution`, `jiraTechnical`, `distributionExplicit`, `distributionExternal` (booleans; `=false` matches only rows explicitly set false — rows where the column is NULL are excluded — `SYS-045`), `vcsPath` (LIKE on a BASE VCS entry), `productionBranch` (LIKE on a BASE VCS entry's `branch`), `canBeParent`. The VCS-entry joins use `distinct` so a multi-entry component is counted once.
  - **Meta option lists** (populate the filter-bar pickers; each returns sorted distinct values **in use**, gated by `ACCESS_COMPONENTS`): `/meta/owners`, `/meta/labels`, `/meta/systems`, `/meta/client-codes`, `/meta/jira-project-keys`, `/meta/parent-component-names` (only keys actually referenced as a parent), `/meta/group-keys` (only groups with ≥1 member). The full master-dictionary variants `/meta/labels/dictionary` and `/meta/systems/dictionary` back the editor multi-selects.
  - **Domain option lists** (populate editor dropdowns, gated by `ACCESS_COMPONENTS`): `/meta/build-systems`, `/meta/repository-types`, `/meta/escrow-generations` (enum-sourced), plus `/meta/java-versions` and `/meta/maven-versions` — the allowed build-tool versions sourced from `components-registry.build-tool-versions.{java,maven}` (application.yml default, per-installation override via service-config), returned numeric-sorted.
  - **Editors** (read-only "who can edit" projection, gated by `ACCESS_COMPONENTS`): `GET /rest/api/4/components/{idOrName}/editors` → `{ componentOwner, releaseManagers[], securityChampions[] }`. Informational only — administrators (`EDIT_ANY_COMPONENT`) may also edit but are not enumerated.
- **Output**: Paginated list of components with summary info
- **Sorting**: By name (default), system, productType, updatedAt
- **Pagination**: Page number + page size (default 20, max 100)

### 1.2 View / Edit Component

- **Input**: Component ID or name
- **Output**: Full component tree — general info, build config, escrow, VCS, distribution, jira, per-field version overrides

**UI layout — per-field inline version overrides:**
- Each field shows its **component default value** in the main tabs (General, Build, VCS, Distribution, Jira, Escrow)
- Each field has a **"+ version override"** action to define value overrides for specific version ranges
- Overrides are displayed **inline below the field** as `[range] → value` entries (e.g., `[1.0, 2.0) → MAVEN`)
- Different fields can have **independent, potentially overlapping** version ranges (e.g., `buildSystem` overridden for `[1.0, 2.0)` while `jiraProjectKey` is overridden for `[1.5, 2.5)`)
- **"Reset to default"** removes a version-level override for a specific field and range
- There is no separate "Previous Versions" tab — all version-specific configuration is visible inline per field

**Computed links (auto-generated, not stored):**

| Link | Source | Displayed |
|------|--------|-----------|
| **Jira** | `jira.projectKey` → `{jira.base.url}/browse/{projectKey}` | From displayName in list; icon in links column |
| **VCS (Bitbucket/GitHub)** | `vcs.url` → converted to web URL | Icon in links column |
| **TeamCity** | Component name → `{teamcity.base.url}/project/{derivedProjectId}` (auto-computed convention) | Icon in links column |
| **DMS** | Only for `distribution.explicit && distribution.external` → `{dms.base.url}/components/{name}` | Icon in links column |

Base URLs for links are configurable per deployment via `registry_config` (same as field config). The "My Components" filter shows only components where `componentOwner` matches the current Keycloak user.

### 1.3 Create Component
- **Required fields**: `name` (unique, alphanumeric + hyphens + underscores, max 255 chars), `componentOwner`
- **Conditionally required**: `releaseManager`, `securityChampion`, `displayName` — required when `distribution.explicit && distribution.external`; `copyright` — required under the same gate only when `components-registry.copyright-path` is configured
- **Optional + unique**: `displayName` — nullable (stored verbatim from the DSL; NOT backfilled to the component key, preserving the legacy v1/v2/v3 `$.name` wire). When set it must be unique across components (400 keyed `displayName` on a duplicate)
- **Optional fields**: productType, system, clientCode, solution, groupId, labels, doc

**Person-field validation (enforced on the v4 write path — see ADR-015).** Restored from the old `EscrowConfigValidator` + the (formerly default-off CI) `ComponentRegistryValidationTask`, and modernised into per-request checks on `POST /rest/api/4/components` and `PATCH /rest/api/4/components/{id}`:

- **`componentOwner`** — required, non-blank, on **every** component. No format pattern.
- **`releaseManager` / `securityChampion`** — required **only when** `distribution.explicit && distribution.external`, and **each list element** must match `^\w+$` (validated per canonical element, so an element like `"alice,bob"` is rejected — it is *not* CSV-split into two usernames). Lists are already trim/dedupe-canonicalized.
- **`copyright`** — required when `distribution.explicit && distribution.external` and `components-registry.copyright-path` is configured. Requiredness validates the final entity state on every create/update; a hidden copyright field is skipped.
- **Active-employee check** — when enabled (`employee-service.enabled=true` + a non-blank `employee-service.url`; off by default), each final-state `componentOwner` / `releaseManager` / `securityChampion` of a **non-archived** component is resolved through employee-service. Archived components skip only the external active lookup; required/pattern checks still run. An **inactive** (`active=false`) or **unknown** (`NotFoundException`) user → **400**. Employee-service **unreachable** (transport/timeout) or the feature **disabled** → the write is **allowed** with a WARN log (**fail-open** — it must not become a hard outage dependency). The required/pattern checks above run regardless of this flag.
- **Timing / grandfathering.** Required/pattern validate the **final entity state** after the patch is applied (so PATCH callers needn't resend unchanged fields). The active-employee check runs only when the request **touches a person field OR flips** `distribution.explicit` / `distribution.external` — flipping the gate to `explicit && external` newly makes RM/SC required and re-validates them even if the PATCH did not set them. When neither person fields nor the gate change, pre-existing saved values are **grandfathered** (not re-checked).
- **Hidden fields** (field-config `visibility: hidden`) are **skipped** entirely — a hidden field is stripped on write, so it cannot be required.
- **Error shape.** All failures are **400** with an `{errorMessage}` body that **starts with the exact field name** (`componentOwner …` / `releaseManager …` / `securityChampion …`), so the Portal maps the error inline.

**Picker / badge lookups** (consumed by the Portal):
- `GET /rest/api/4/components/meta/employees?search=<q>` → `[{username, active}]` — an authenticated exact `getEmployee` probe (0/1 result); the employee-service client has no prefix search, so typeahead suggestions come from `/meta/owners` and this annotates the active flag.
- `POST /rest/api/4/components/meta/employees/status` body `[username…]` → `{username: active|null}` — batch exact lookups; `null` = unknown/unavailable/disabled (the Portal renders no badge). Both are `ACCESS_COMPONENTS`-gated and fail-open.
- **Nested creation**: Can include build, escrow, VCS, distribution, jira configs in single request
- **Validation**: Name uniqueness — a duplicate component key on **create** returns **400** with a
  field-prefixed `name:` message (so the Portal routes it inline); a duplicate on **rename**
  (`PATCH name`) returns **409 Conflict** (`ComponentNameConflictException`). Plus field format validation.
- **Default application**: Component defaults (see 7.2) are applied to all absent fields
- **Audit**: CREATE event logged with full new_value

**UI — Create Component dialog:**

1. **Profile selection** (future feature, out of scope for initial implementation) — admin-defined profiles (e.g., "Gradle Library", "Spring Boot Service", "Kotlin DSL Plugin") that pre-fill build, VCS, and escrow settings. For now, component defaults serve as a single implicit profile.
2. **Component name** (required) + **display name** (optional; required for explicit+external components)
3. **Owner** — pre-filled with current user
4. **TeamCity integration** — optional checkbox "Create TeamCity project". When checked, user selects a parent TeamCity project from a dropdown/search. On component creation, the system calls TeamCity API to create a sub-project. (Out of scope for initial implementation — documented as future integration point.)
5. **VCS repository URL** — optional, for linking to existing repo
6. **Remaining fields** — pre-filled from component defaults, editable per field config visibility rules

### 1.4 Update Component
- **Input**: Component ID + partial or full update payload
- **Behavior**: JSON Merge Patch semantics (RFC 7396):
  - **Field present with value** → set to that value
  - **Field absent** → not changed
  - **Field set to `null`** → clear/reset to component default (removes override)
- **"Reset to default"**: Setting a field to `null` in the update request removes the version-level or component-level override and reverts the field to the inherited default. This is how the UI "Reset to default" action is implemented.
- **Nested updates**: Build, escrow, VCS, distribution, jira can be updated in same request
- **Optimistic locking**: `@Version` field prevents lost updates (409 Conflict on stale version)
- **Audit**: UPDATE event logged with old_value, new_value, change_diff

#### Field-format validation (create + update)

Beyond the structural/enum checks (name uniqueness, `productType` / `buildSystem` /
`repositoryType` / `packageType` / `escrow.generation` enums, version-range syntax,
per-field range non-overlap), the v4 write path enforces the following single-field
shape/format rules on both `POST /rest/api/4/components` and
`PATCH /rest/api/4/components/{id}`. Each is a field-name-prefixed `400 Bad Request`
(`{ "errorMessage": "<field> …" }`). These restore the corresponding checks the legacy
`EscrowConfigValidator` ran at config-load time.

| Field | Rule | Notes |
|-------|------|-------|
| `clientCode` | Matches `[A-Z_0-9]+` when present | Blank/whitespace is treated as "no client code" and skipped. |
| `copyright` | Must name a file under the configured copyright directory | The supported list is read from `components-registry.copyright-path` (same source as the read-side `CopyrightService`). When that path is not configured (or cannot be listed), the supported-list check is a no-op. Blank is allowed except when the explicit+external requiredness rule above applies. |
| `artifactIds[].artifactPattern` | Must be a compilable regular expression (and non-blank) | Validated on both create and the PATCH REPLACE path. |
| `buildToolBeans[].beanType` | Must be specified (non-blank) | The v4 build-tool is a typed bean whose identifying field is `beanType`; this is the v4 analogue of the legacy build-tool per-field requireds (`name` / `escrowEnvironmentVariable` / `sourceLocation` / `targetLocation`). The legacy `Tool` fields themselves live in the global tools master, which is not writable through the component create/update payload. |

These format checks are skipped for a field whose admin field-config visibility is
`HIDDEN`. On `PATCH`, the hidden value is also stripped before persistence.

#### Intentional legacy-validation relaxations

- `displayName` is **nullable** + UNIQUE at the DB layer. It is stored **verbatim** from the DSL —
  it is NOT backfilled to the component key, because the legacy v1/v2/v3 `$.name` must keep serving
  `null` for components without a `componentDisplayName` (prod 2.0.87 byte-compat). It is
  **required only for explicit+external components** (`distribution.explicit && distribution.external`),
  mirroring the pre-existing `EscrowConfigValidator.validateExplicitExternalComponent` rule. On
  **create/update**, a value already used by another component is rejected with a **400 keyed
  `displayName`**; on **update** a blank value clears it to `null` (except for an explicit+external
  component, where the requiredness check then rejects it). The import stores the DSL value verbatim
  and fails fast on duplicate non-null names (see schema-spec). The Portal uses the component key as
  the stable identity, so the display label is optional except under the explicit+external gate.
- Legacy hotfix version-format relationship checks are not enforced on v4 writes.
  Hotfix formats are inherited/read-only in the Portal, while permissive storage
  preserves imported configurations and resolver compatibility.

### 1.5 Delete Component
- **Behavior**: Soft delete — sets `archived = true`
- **Cascading**: Does NOT delete versions or sub-components
- **Undo**: Can un-archive by updating `archived = false`
- **Hard delete**: Admin-only, removes component and all related data (with CASCADE)
- **Audit**: DELETE event logged
- **JIRA guard**: No runtime JIRA-existence check. The legacy guard compared two
  config-repository snapshots during CI; the v4 delete path archives the component
  in place and intentionally does not make CRS deletion depend on JIRA availability.

### 1.6 View as Code

- **Endpoint**: `GET /rest/api/4/components/{idOrName}/as-code` — auth `ACCESS_COMPONENTS`
- **Output**: `text/plain;charset=UTF-8` — a human-readable, **Groovy-style** rendering of the
  component definition (the reverse of the legacy Groovy import). `Content-Disposition: inline;
  filename="{componentKey}.groovy"`.
- **Two modes**:
  - **FULL** (no `version` param): the whole component **with all version ranges**, delta-style —
    a top-level block with the per-component fields + BASE-row aspects, followed by one
    `"<range>" { … }` block per distinct override range carrying only the fields that range
    overrides (a per-range `field = null` line denotes an explicit null-clear).
  - **RESOLVED** (`?version=<v>`): the component flattened/merged for one concrete version — a
    single block, no range sub-blocks. The scalar + child-collection merge reuses the same
    primitives as version resolution, so values match the v2 version-resolution endpoints. (One
    deliberate difference: distribution `$version` substitution — a runtime concern — is not
    applied, so distribution patterns show the same templates as the FULL view.)
- **404** when the component is unknown, or (RESOLVED) when no configuration resolves for the
  version (no BASE row / unparseable version). A version that simply matches no override range
  still renders the BASE view (resolver fallback semantics).
- **Read-only**: the rendering is a projection; it is not parsed back (no GroovyShell / legacy
  escrow libraries are involved — it is a plain string builder). Surfaced in the Portal as the
  read-only **"As Code"** tab on the component page (syntax-highlighted, with Full/Resolved toggle).

## 2. Version Range Management

Version overrides are **per-field**, not per-component-version. Each field in each parameter group (build, VCS, jira, distribution, escrow) can have its own independent set of version ranges.

### 2.1 Overlap Validation

Version ranges for the **same field** must not overlap. This is enforced on create and update:

- **Per-field non-overlap**: For any given field (e.g., `build.buildSystem`), the set of version ranges must be mutually exclusive. Attempting to add `[1.0, 3.0)` when `[2.0, 4.0)` already exists for the same field → **409 Conflict**
- **Cross-field independence**: Ranges for different fields may freely overlap. E.g., `build.buildSystem` overridden for `[1.0, 2.0)` and `jira.projectKey` overridden for `[1.5, 2.5)` is valid
- **Range format**: Maven version range syntax — `[` inclusive, `(` exclusive, e.g., `[1.0, 2.0)`, `(,3.0]`, `[4.0, )`
- **Boundary precision**: Overlap is checked using proper boundary comparison — `[1.0, 2.0)` and `[2.0, 3.0)` do **not** overlap (2.0 is exclusive in the first, inclusive in the second); `[1.0, 2.0]` and `[2.0, 3.0)` **do** overlap (2.0 is inclusive in both)
- **UI enforcement**: The "Add" action in the inline override form validates overlap client-side before submission; the server re-validates independently

### 2.2 Add Field Version Override
- **Input**: Component ID + field path (e.g., `build.buildSystem`) + version range string (e.g., `[1.0, 2.0)`) + override value
- **Validation**: Version range format + per-field non-overlap (see section2.1)
- **Overridable fields**: configurable per deployment via field configuration (see section7.1). By default, all fields within build, escrow, VCS, distribution, jira groups (plus `artifactIds` and `groupId`) are overridable. Admin can restrict which fields allow version overrides

### 2.3 Update Field Version Override
- **Input**: Override ID + new value (or new version range)
- **Behavior**: Updates the override value or range for a specific field
- **Validation**: If range is changed, per-field non-overlap is re-validated excluding the current override (see section2.1)

### 2.4 Delete Field Version Override
- **Behavior**: Removes the version override for a specific field and range
- **Effect**: The field reverts to the component default for that version range

### 2.5 Cross-Component Integrity Validation

In addition to the per-component overlap rules above (section 2.1), `POST /rest/api/4/components` (create), `PATCH /rest/api/4/components/{id}` (update), **and the field-override sub-resource** (`POST`/`PATCH /rest/api/4/components/{id}/field-overrides[/{overrideId}]`) enforce a set of **cross-component** and malformed-input rules at write time. These restore the composite checks the legacy `EscrowConfigValidator` ran at config-load time (audit `VALIDATION-PARITY-2026-06-03.md`, rows #6/#10/#20/#24/#25/#26/#28/#29). See `tech-debt/012-pre-publish-validation-parity.md` for the parity ledger.

The checks validate the **final persisted state** (after the patch is applied and flushed) using **self-excluding** queries — a component never conflicts with itself, while conflicts with other components are still rejected whenever validation is triggered. A field-override write that introduces a `mavenArtifacts` / `dockerImages` coordinate on an override row is re-validated against all of the owning component's configuration rows (base + overrides), so a collision cannot be slipped in through the sub-resource.

Conflicts with **other** components → **409 Conflict** (`CrossComponentConflictException`):
- **Duplicate `groupId:artifactId` in overlapping ranges** — two components must not declare the same maven `(groupPattern, artifactPattern)` for intersecting version ranges. Intersection uses the same Maven boundary semantics as section 2.1.
- **Jira `(projectKey, versionPrefix)` uniqueness among non-archived components** — each `(projectKey, versionPrefix)` pair maps to at most one non-archived component. A `null` version prefix is its own bucket. Archived components are exempt and do not claim a bucket.
- **Docker image-name global uniqueness** — a docker image name (e.g. `registry.example/app`) may be declared by at most one component across the whole registry.

Malformed-input rules → **400 Bad Request** (`IllegalArgumentException`, field-name-prefixed message):
- **Explicit+external requires ≥1 distribution coordinate** — when `distributionExplicit && distributionExternal`, the component must define at least one maven artifact, docker image, or package on some configuration row.
- **`groupId` supported prefix** — every maven `groupPattern` element must start with a configured `components-registry.supportedGroupIds` prefix. When that list is unconfigured/empty the check is skipped (logged), not enforced.
- **Archived ≠ explicit+external** — an archived component cannot be explicitly+externally distributed.
- **Doc-component existence** — every `docs[].docComponentKey` must reference an existing component. The reference is a soft string ref (no FK, see `schema-spec.md:288`), so existence is verified in the service layer. This check runs **post-flush** (alongside the 409 checks), so a component may reference its **own** key (self-documenting) without a false 400 — the component's own row exists by then, and the own key is excluded explicitly regardless of persistence order.

**Performance**: Docker image and Jira collision checks use indexed equality queries (`image_name`, `jira_project_key`). Maven collision validation loads projected artifact rows from other components and performs legacy-compatible wildcard/regex/CSV pattern overlap plus Maven range intersection in-memory; exact SQL equality cannot safely narrow those candidates. The docker check is backed by `idx_dist_docker_image_name` on `distribution_docker_images(image_name)`.

## 3. Search & Lookup (Existing API Behavior)

All existing search/lookup operations must return identical results from DB:

### 3.1 Find by Artifact
- **Input**: ArtifactDependency (groupId, artifactId)
- **Output**: Matching VersionedComponent
- **Index**: `component_artifact_ids.artifact_id`, `component_version_artifact_ids.artifact_id`

### 3.2 Find by Docker Image
- **Input**: Set of Image (name)
- **Output**: Set of ComponentImage matches
- **Index**: `distribution_artifacts.image_name`

### 3.3 Filter by VCS Path
- **Input**: VCS repository URL path
- **Output**: Components whose VCS settings match
- **Index**: `vcs_settings.url`

### 3.4 Jira Project Lookup
- **Input**: Jira projectKey (+ optional version)
- **Output**: Component version ranges, distributions, VCS settings for that project
- **Index**: `jira_component_configs.project_key`

## 4. Audit Log

### 4.1 View Entity History
- **Input**: Entity type (component, version, etc.) + entity ID; optional `includeMigrated` (default `false`)
- **Output**: Paginated list of changes, newest first
- **Each entry**: action, changedBy, changedAt, oldValue (JSONB), newValue (JSONB), changeDiff (JSONB)
- **Migration baseline hidden**: `action = MIGRATED` rows (git-history first-appearance, one per component) are hidden unless `includeMigrated=true` — the Portal "Show migration" toggle (`SYS-049`).
- **No empty rows**: a save that changes nothing writes no entry (`SYS-048`); field-override (version-range) edits are recorded as Component `UPDATE` (`SYS-050`).

### 4.2 Global Recent Changes
- **Endpoint**: `GET /rest/api/4/audit/recent`
- **Input**: Optional filter query params (all independently optional, ANDed when combined; contract `SYS-036`):
  - `entityType` — currently only `Component` is emitted (case-sensitive; `cb.equal`). `FieldOverride` and other entity types are reserved for future audit instrumentation.
  - `entityId` — UUID of a specific entity (combine with `entityType` for entity-scoped history reachable via the same query as user/source filters)
  - `changedBy` — username from `audit_log.changed_by`
  - `source` — currently only `api` and `git-history` are emitted. Other values are reserved for future writers.
  - `action` — `CREATE` \| `UPDATE` \| `DELETE` \| `RENAME` \| `MIGRATED`
  - `from`, `to` — ISO-8601 instants forming a half-open `[from, to)` window over `audit_log.changed_at`
  - `includeMigrated` — default `false`; `MIGRATED` (migration baseline) rows are hidden unless this is `true` or an explicit `action=MIGRATED` filter is supplied (`SYS-049`)
- **Output**: Paginated feed of audit rows newest-first (default sort `changedAt DESC`; caller-supplied `sort=` overrides)
- **Page size**: Spring Data `Pageable`; defaults to the global `spring.data.web.pageable.default-page-size`

### 4.3 Change Diff
- **Format**: JSON object showing only changed fields
- **Example**:
```json
{
  "diff": {
    "displayName": { "old": "My Component", "new": "My Updated Component" },
    "build.javaVersion": { "old": "11", "new": "21" }
  }
}
```

## 5. Data Import

All admin endpoints below live under `POST /rest/api/4/admin/**` and require `IMPORT_DATA` permission (class-level `@PreAuthorize("@permissionEvaluator.canImport()")` on `AdminControllerV4`).

### 5.1 Bulk Import from Git (synchronous)
- **Endpoint**: `POST /admin/migrate-components` (alias: `POST /admin/import`)
- **Process**: Clone Git → parse Groovy/Kotlin DSL → map to entities → save to DB. Synchronous — request blocks until done.
- **Validation**: After import, deep-compare Git vs DB resolver output per component; flip `source=db` only if match.
- **Idempotency**: Re-import overwrites existing data for matching component names.
- **Output**: `BatchMigrationResult` — count of imported components, versions, errors.
- **Note**: For long-running migrations (≈900+ components) prefer the async variant in **5.4**.

### 5.2 Per-component Import
- **Endpoint**: `POST /admin/migrate-component/{name}?dryRun=true|false`
- **Process**: Same pipeline as 5.1, scoped to one component.
- **Output**: `MigrationResult`.

### 5.3 Migrate Defaults
- **Endpoint**: `POST /admin/migrate-defaults`
- **Process**: Imports `Defaults.groovy` into `component_defaults` (nested keys: build, jira, distribution, vcs, escrow, doc, deprecated, octopusVersion).
- **Output**: Map of imported keys.

### 5.4 Async Bulk Migration (recommended for full migration)
- **Endpoint**: `POST /admin/migrate` → `202 Accepted` (newly started) or `409 Conflict` (job already running). Body: `MigrationJobResponse`. See `MIG-027` in [requirements-migration.md](requirements-migration.md) for the full contract.
- **Polling**: `GET /admin/migrate/job` → `200 OK` with `MigrationJobResponse` (running/completed) or `404 Not Found` if no job has been started since pod startup.
- **Re-run guard**: a second `POST /admin/migrate` while a job is `RUNNING` returns 409 with the existing job's state — the SPA "attaches" to the in-flight job rather than spawning a duplicate.
- **State persistence**: in-memory only. Pod restart loses progress; SPA sees `404` on next poll. Tracked in `MIG-028`.

### 5.5 Migration Status
- **Endpoint**: `GET /admin/migration-status`
- **Output**: `MigrationStatus` — counts of components currently routed to `db` vs `git`.

### 5.6 Validate Migration
- **Endpoint**: `POST /admin/validate-migration/{name}`
- **Output**: `ValidationResult` — Git vs DB resolver diff for the named component.

### 5.7 Git History Backfill
- **Endpoint**: `POST /admin/migrate-history?toRef={ref}&reset={true|false}`
- **Purpose**: Replay git commit history of the legacy DSL repo into `audit_log` so the UI's history view shows changes that pre-date the DB migration.
- **Idempotency**: One-row state in `git_history_import_state` (PK `import_key`, status `IN_PROGRESS` / `COMPLETED` / `FAILED` — see `GitHistoryImportStatus`). A second call with `reset=false` against an already-completed import is a no-op; `reset=true` clears state and re-runs.
- **Audit source marker**: rows written by this endpoint carry `audit_log.source = 'git-history'`; runtime API events carry `'api'`. See V5 schema migration.
- **First-appearance action**: a component's first appearance is recorded with `action = MIGRATED` (not `CREATE`); subsequent historical changes stay `UPDATE`/`DELETE`. `MIGRATED` rows are hidden from the audit views by default (`SYS-049`).
- **Output**: `HistoryImportResult` — `{ targetRef, targetSha, processedCommits, skippedNoGroovy, skippedParseError, skippedUnknownNames, auditRecords, durationMs }`.
- **Contract**: `MIG-026` in [requirements-migration.md](requirements-migration.md).

### 5.8 Export
- **Status**: Stub — `GET /admin/export` returns `{"status": "not_implemented"}`. Intentionally not part of the spec until implemented; tracked as backlog.

## 6. Authorization Rules

The canonical role/permission matrix, filter-chain rules, and Keycloak role naming convention live in [ADR-004 — Authentication & Authorization via Keycloak](adr/004-auth-keycloak.md). That document is the source of truth; this section only sketches the shape so a reader of the functional spec has enough context without jumping.

- **Permissions** (9): `ACCESS_COMPONENTS`, `CREATE_COMPONENTS`, `EDIT_ANY_COMPONENT`, `ARCHIVE_COMPONENTS`, `RENAME_COMPONENTS`, `DELETE_COMPONENTS`, `IMPORT_DATA`, `ACCESS_AUDIT`, `EDIT_METADATA` (edit component-configuration metadata — gates the Portal Field-Overrides edit surface; `ROLE_ADMIN` only).
- **Roles** (4): `ROLE_ANONYMOUS` → public reads only; `ROLE_COMPONENTS_REGISTRY_VIEWER`; `ROLE_COMPONENTS_REGISTRY_EDITOR`; `ROLE_ADMIN` (super-admin, reuses the existing Keycloak `ADMIN` realm-role). No separate `COMPONENTS_REGISTRY_ADMIN` — we piggyback on the platform admin role.
- **v1/v2/v3 reads**: public (Phase 1 backward compat).
- **v4 GET `/components/**` and `/config/**`**: public via `ROLE_ANONYMOUS` → `ACCESS_COMPONENTS`. All other v4 endpoints (writes, admin, audit) require authentication + the permission named in ADR-004.
- **`PATCH /rest/api/4/components/{id}`** is field-level guarded: a plain edit needs `ACCESS_COMPONENTS && canEditComponent(id)` where `canEditComponent` means component owner / release manager / security champion, or `EDIT_ANY_COMPONENT` for admin bypass. Flipping `archived` additionally needs `ARCHIVE_COMPONENTS`; changing `name` (rename) additionally needs `RENAME_COMPONENTS`. `ARCHIVE_COMPONENTS` and `RENAME_COMPONENTS` are reserved for `ROLE_ADMIN` today — `ROLE_COMPONENTS_REGISTRY_EDITOR` cannot archive or rename through this endpoint.
- **Field-override CRUD** uses the same component-scoped edit gate as plain component edits: `ACCESS_COMPONENTS && canEditComponent(id)`.

## 7. Field Configuration & Defaults (Admin)

The system is used across multiple organizations with similar but not identical processes. The REST API contract is unified, but each deployment can customize field visibility and default values without code changes. See [ADR-011](adr/011-field-configuration.md).

### 7.1 Field Configuration

Admin controls per-field behavior:

| Visibility | UI | API |
|---|---|---|
| `editable` | Input field, user can modify | Accepts value; applies default if absent |
| `readonly` | Shown grayed out | Ignores client value; applies server-side value |
| `hidden` | Not rendered | Ignores client value; applies default silently |

**Version override eligibility** — an additional per-field flag:

| Flag | UI | API |
|---|---|---|
| `overridable: true` (default) | Field shows "+" version override" button | Accepts version override creation for this field |
| `overridable: false` | No override button, field value is the same across all versions | Rejects version override creation (400) |

**Example:** Organization A always uses `system = "CLASSIC"` — admin sets `system` to `hidden` with default `"CLASSIC"`. Users never see it, but API always returns it.

**Example:** Organization B does not want per-version Jira project key changes — admin sets `jira.projectKey` to `overridable: false`. The field is still editable at the component level, but cannot have version-specific overrides.

### 7.2 Component Defaults

Replaces `Defaults.groovy`. Admin defines default values applied when creating a new component:
- Build defaults (Java version, build system, Gradle version)
- Copyright template
- Escrow defaults (reusable, generation mode)
- Jira version format templates
- Any Tier 3 metadata fields

**Behavior on create:**
1. Load `component_defaults`
2. Merge with request: request values override defaults; absent fields get defaults
3. Apply `field_config` enforcement: hidden/readonly fields always get server-side value
4. Validate merged result
5. Save

### 7.3 Audit

Changes to field configuration and component defaults are recorded in the audit log (entity_type = `registry_config`).

## 8. Error Handling

| Scenario | HTTP Status | Response |
|----------|-------------|----------|
| Component not found | 404 | `{ "error": "Component not found", "id": "..." }` |
| Duplicate name on **create** | 400 | `{ "errorMessage": "name: a component with name '...' already exists" }` (field-prefixed → Portal routes inline) |
| Duplicate name on **rename** (PATCH name) | 409 | `{ "errorMessage": "Component with name '...' already exists" }` (`ComponentNameConflictException`) |
| Duplicate `displayName` (create/update) | 400 | `{ "errorMessage": "displayName: a component with display name '...' already exists" }` |
| Optimistic lock conflict | 409 | `{ "error": "Component was modified by another user" }` |
| Validation failure | 400 | `{ "errors": [{ "field": "name", "message": "must not be blank" }] }` |
| Unauthorized | 401 | Standard Spring Security response |
| Forbidden | 403 | `{ "error": "Insufficient permissions" }` |
| Import failure | 422 | `{ "error": "Import failed", "details": [...] }` |

## 9. Service Info & Identity Endpoints

These endpoints serve cross-cutting needs (Portal footer, current-user display) and are not tied to component CRUD.

### 9.1 Build Info
- **Endpoint**: `GET /rest/api/4/info`
- **Auth**: Anonymous (`permitAll`) — Portal footer must render before login.
- **Output**: `{ "name": "<artifact-name>", "version": "<build-version>" }` (sourced from Spring Boot `BuildProperties`).
- **Contract**: `SYS-033` in [requirements-common.md](requirements-common.md).
- **Why anonymous on this side too**: Portal also exposes `/portal/info` for its own build label; both endpoints are explicitly `permitAll` end-to-end so the footer never blocks on a 401 round-trip. See ADR-012.

### 9.2 Current User
- **Endpoint**: `GET /auth/me` (note: outside the `/rest/api/4` tree — top-level `/auth`).
- **Auth**: Authenticated. Returns 401 if no JWT.
- **Output**: `User` from `octopus-cloud-commons` — `{ username, roles, groups }`.
- **Contract**: `SYS-034` in [requirements-common.md](requirements-common.md).
