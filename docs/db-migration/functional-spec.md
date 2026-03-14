# Functional Specification: Components Registry DB Migration

## Status
**Draft** | Date: 2026-03-08

---

## 1. Component Management

### 1.1 List Components
- **Input**: Optional filters — system, clientCode, productType, archived, search (name/displayName)
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
- **Conditionally required**: `releaseManager`, `securityChampion`, `copyright` — required when `distribution.explicit && distribution.external` (enforced by current `EscrowConfigValidator`)
- **Optional fields**: displayName, productType, system, clientCode, solution, groupId, labels, doc
- **Nested creation**: Can include build, escrow, VCS, distribution, jira configs in single request
- **Validation**: Name uniqueness (409 Conflict if exists), field format validation
- **Default application**: Component defaults (see 7.2) are applied to all absent fields
- **Audit**: CREATE event logged with full new_value

**UI — Create Component dialog:**

1. **Profile selection** (future feature, out of scope for initial implementation) — admin-defined profiles (e.g., "Gradle Library", "Spring Boot Service", "Kotlin DSL Plugin") that pre-fill build, VCS, and escrow settings. For now, component defaults serve as a single implicit profile.
2. **Component name** (required) + **display name** (optional, defaults to name)
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

### 1.5 Delete Component
- **Behavior**: Soft delete — sets `archived = true`
- **Cascading**: Does NOT delete versions or sub-components
- **Undo**: Can un-archive by updating `archived = false`
- **Hard delete**: Admin-only, removes component and all related data (with CASCADE)
- **Audit**: DELETE event logged

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
- **Input**: Entity type (component, version, etc.) + entity ID
- **Output**: Paginated list of changes, newest first
- **Each entry**: action, changedBy, changedAt, oldValue (JSONB), newValue (JSONB), changeDiff (JSONB)

### 4.2 Global Recent Changes
- **Input**: Optional filters — user, date range, entity type, action type
- **Output**: Paginated feed of all recent changes across all entities
- **Default**: Last 7 days, page size 50

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

### 5.1 Import from DSL
- **Input**: Admin triggers import (optional: Git repo URL, branch, tag)
- **Process**: Clone Git → parse Groovy DSL → parse Kotlin DSL → map to entities → save to DB
- **Validation**: After import, deep-compare Git vs DB resolver output per component; flip `source=db` only if match
- **Idempotency**: Re-import overwrites existing data for matching component names
- **Output**: Import report — count of imported components, versions, errors

### 5.2 Export to JSON
- **Input**: Optional component filter
- **Output**: JSON file with all component configurations
- **Format**: Matches v4 API response structure

## 6. Authorization Rules

### 6.1 Role → Permission Mapping

| Keycloak Role | Permissions |
|---------------|------------|
| REGISTRY_READER | ACCESS_COMPONENTS, ACCESS_AUDIT |
| REGISTRY_EDITOR | all READER permissions + EDIT_COMPONENTS |
| REGISTRY_ADMIN | all EDITOR permissions + DELETE_COMPONENTS, IMPORT_DATA |
| Component Owner | same as EDITOR, scoped to owned components |

### 6.2 Operation → Permission Matrix

| Operation | Required Permission | Additional Check |
|-----------|-------------------|------------------|
| List/View components (v1/v2/v3) | None (public, backward compat) | — |
| List/View components (v4) | ACCESS_COMPONENTS | — |
| Create component | EDIT_COMPONENTS | — |
| Update component | EDIT_COMPONENTS | Owner check: if `componentOwner` is set, only the owner or ADMIN can update |
| Soft delete component (archive) | DELETE_COMPONENTS | — |
| Hard delete component | ADMIN role only | Irreversible — removes component and all related data (CASCADE) |
| Manage versions | EDIT_COMPONENTS | — |
| View audit | ACCESS_AUDIT | — |
| Import data | IMPORT_DATA | — |
| Export data | ACCESS_COMPONENTS | — |
| View field config | ACCESS_COMPONENTS | Read-only (UI uses to render forms) |
| Edit field config | ADMIN role only | Controls which fields are visible/editable per deployment |
| View component defaults | ACCESS_COMPONENTS | Read-only |
| Edit component defaults | ADMIN role only | Replaces Defaults.groovy; applied to new components |

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
| Duplicate name | 409 | `{ "error": "Component with name '...' already exists" }` |
| Optimistic lock conflict | 409 | `{ "error": "Component was modified by another user" }` |
| Validation failure | 400 | `{ "errors": [{ "field": "name", "message": "must not be blank" }] }` |
| Unauthorized | 401 | Standard Spring Security response |
| Forbidden | 403 | `{ "error": "Insufficient permissions" }` |
| Import failure | 422 | `{ "error": "Import failed", "details": [...] }` |
