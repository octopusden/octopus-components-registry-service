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

### 1.2 View Component
- **Input**: Component ID or name
- **Output**: Full component tree — general info, build config, escrow, VCS, distribution, jira, all version ranges with their overrides, sub-components
- **Inheritance display**: Version-level fields show whether they are inherited from component-level or overridden

### 1.3 Create Component
- **Required fields**: `name` (unique, alphanumeric + hyphens, max 255 chars)
- **Optional fields**: displayName, productType, componentOwner, releaseManager, securityChampion, system, clientCode, solution, groupId, copyright, labels, doc
- **Nested creation**: Can include build, escrow, VCS, distribution, jira configs in single request
- **Validation**: Name uniqueness (409 Conflict if exists), field format validation
- **Audit**: CREATE event logged with full new_value

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

| Operation | Required Permission | Additional Check |
|-----------|-------------------|------------------|
| List/View components (v1/v2/v3) | None (public, backward compat) | — |
| List/View components (v4) | ACCESS_COMPONENTS | — |
| Create component | EDIT_COMPONENTS | — |
| Update component | EDIT_COMPONENTS | Owner check (optional) |
| Delete component | DELETE_COMPONENTS | — |
| Manage versions | EDIT_COMPONENTS | — |
| View audit | ACCESS_AUDIT | — |
| Import data | IMPORT_DATA | — |
| Export data | ACCESS_COMPONENTS | — |

## 7. Error Handling

| Scenario | HTTP Status | Response |
|----------|-------------|----------|
| Component not found | 404 | `{ "error": "Component not found", "id": "..." }` |
| Duplicate name | 409 | `{ "error": "Component with name '...' already exists" }` |
| Optimistic lock conflict | 409 | `{ "error": "Component was modified by another user" }` |
| Validation failure | 400 | `{ "errors": [{ "field": "name", "message": "must not be blank" }] }` |
| Unauthorized | 401 | Standard Spring Security response |
| Forbidden | 403 | `{ "error": "Insufficient permissions" }` |
| Import failure | 422 | `{ "error": "Import failed", "details": [...] }` |
