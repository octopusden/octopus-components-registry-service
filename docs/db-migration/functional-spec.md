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
- **Output**: Full component tree — general info, build config, escrow, VCS, distribution, jira, version ranges with overrides

**UI layout — version-aware editing:**
- **Head version** (latest open range, e.g., `[3.0, )`) — its resolved configuration (component defaults + head overrides) is shown in the main tabs (General, Build, VCS, Distribution, Jira, Escrow). Editing here modifies the head version.
- **Previous versions** (e.g., `[2.0, 3.0)`, `[1.0, 2.0)`) — shown in a separate "Previous Versions" tab. Each version is a collapsible section showing only its overrides relative to component defaults. Old versions are read-only by default; overrides can be added/removed.
- **Inheritance display**: Each field shows whether it uses the component default or is overridden in the current version (visual indicator + "Reset to default" action)

**Computed links (auto-generated, not stored):**

| Link | Source | Displayed |
|------|--------|-----------|
| **Jira** | `jira.projectKey` → `{jira.base.url}/browse/{projectKey}` | From displayName in list; icon in links column |
| **VCS (Bitbucket/GitHub)** | `vcs.url` → converted to web URL | Icon in links column |
| **TeamCity** | Component name → `{teamcity.base.url}/project/{derivedProjectId}` (auto-computed convention) | Icon in links column |
| **DMS** | Only for `distribution.explicit && distribution.external` → `{dms.base.url}/components/{name}` | Icon in links column |

Base URLs for links are configurable per deployment via `registry_config` (same as field config). The "My Components" filter shows only components where `componentOwner` matches the current Keycloak user.

### 1.3 Create Component
- **Required fields**: `name` (unique, alphanumeric + hyphens + underscores, max 255 chars)
- **Optional fields**: displayName, productType, componentOwner, releaseManager, securityChampion, system, clientCode, solution, groupId, copyright, labels, doc
- **Nested creation**: Can include build, escrow, VCS, distribution, jira configs in single request
- **Validation**: Name uniqueness (409 Conflict if exists), field format validation
- **Default application**: Component defaults (see 7.2) are applied to all absent fields
- **Audit**: CREATE event logged with full new_value

**UI — Create Component dialog:**

1. **Profile selection** (future feature, out of scope for initial implementation) — admin-defined profiles (e.g., "Gradle Library", "Spring Boot Service", "Kotlin DSL Plugin") that pre-fill build, VCS, and escrow settings. For now, component defaults serve as a single implicit profile.
2. **Component name + display name** — required input
3. **Owner** — pre-filled with current user
4. **TeamCity integration** — optional checkbox "Create TeamCity project". When checked, user selects a parent TeamCity project from a dropdown/search. On component creation, the system calls TeamCity API to create a sub-project. (Out of scope for initial implementation — documented as future integration point.)
5. **VCS repository URL** — optional, for linking to existing repo
6. **Remaining fields** — pre-filled from component defaults, editable per field config visibility rules

### 1.4 Update Component
- **Input**: Component ID + partial or full update payload
- **Behavior**: Merge update — only provided fields are changed, null/absent fields remain unchanged
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

### 2.1 Add Version Range
- **Input**: Component ID + version range string (e.g., `[1.0,2.0)`) + optional overrides
- **Validation**: Version range format, uniqueness within component (409 if overlapping)
- **Overridable sections**: build, escrow, VCS, distribution, jira, artifactIds, groupId

### 2.2 Update Version Range
- **Input**: Version ID + update payload
- **Behavior**: Merge update on version-specific overrides

### 2.3 Delete Version Range
- **Behavior**: Hard delete of version and all its override configs
- **Cascade**: Removes version's build, escrow, VCS, distribution, jira configs

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
- **Validation**: After import, dual-read mode compares results
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
| Edit component defaults | ADMIN role only | Replaces Default.groovy; applied to new components |

## 7. Field Configuration & Defaults (Admin)

The system is used across multiple organizations with similar but not identical processes. The REST API contract is unified, but each deployment can customize field visibility and default values without code changes. See [ADR-011](adr/011-field-configuration.md).

### 7.1 Field Configuration

Admin controls per-field behavior:

| Visibility | UI | API |
|---|---|---|
| `editable` | Input field, user can modify | Accepts value; applies default if absent |
| `readonly` | Shown grayed out | Ignores client value; applies server-side value |
| `hidden` | Not rendered | Ignores client value; applies default silently |

**Example:** Organization A always uses `system = "CLASSIC"` — admin sets `system` to `hidden` with default `"CLASSIC"`. Users never see it, but API always returns it.

### 7.2 Component Defaults

Replaces `Default.groovy`. Admin defines default values applied when creating a new component:
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
