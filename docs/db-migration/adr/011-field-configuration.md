# ADR-011: Configurable Field Visibility, Editability, and Defaults

## Status
Proposed

## Context

The Components Registry is used across **multiple organizations** with similar but not identical processes. Each organization has its own:

- Set of relevant fields (e.g., one org uses `system` = always "CLASSIC", another has multiple systems)
- Default values for new components (e.g., default build settings, copyright templates)
- Fields that are mandatory vs optional vs irrelevant
- Naming conventions and enumeration values (product types, build systems)

In the current Groovy DSL model:

- `Default.groovy` defines default values for all components (e.g., `system = "CLASSIC"`, default build settings, copyright)
- Some fields are always the same in a given installation and showing/editing them adds clutter (e.g., `system` is always `"CLASSIC"` in one org)
- There is no way to control which fields are visible or required in the UI
- Adapting to a new organization requires code/DSL changes

When migrating to a database-backed model with a web UI, we need:

1. **Global field configuration** — control which fields are visible, read-only, or editable in the UI, per deployment/organization
2. **Default values** — a replacement for `Default.groovy` that admins can manage through the UI (includes copyright, build defaults, etc.)
3. **Role-based editability** — defaults and field config should be editable only by admins
4. **Zero-code customization** — adapting the system to a new organization should require only configuration changes, not code
5. **Unified API contract** — the REST API (v1/v2/v3/v4) is identical across all deployments; field configuration affects only UI rendering, default values, and server-side validation — not the API schema

## Decision

**Introduce a Field Configuration Registry** — a structured JSON document stored in the database, editable via admin UI, that controls field behavior globally.

### Field Configuration Schema

```json
{
  "component": {
    "system": {
      "visibility": "hidden",
      "default": "CLASSIC",
      "description": "Component system classification"
    },
    "productType": {
      "visibility": "editable",
      "default": "PT_K",
      "options": ["PT_K", "PT_C", "PT_D", "PT_D_DB"],
      "description": "Product type"
    },
    "componentOwner": {
      "visibility": "editable",
      "required": true,
      "description": "Component owner"
    },
    "displayName": {
      "visibility": "editable",
      "description": "Display name"
    },
    "releaseManager": {
      "visibility": "readonly",
      "description": "Release manager (set by admin)"
    },
    "solution": {
      "visibility": "hidden",
      "default": false
    }
  },
  "build": {
    "javaVersion": {
      "visibility": "editable",
      "default": "21",
      "description": "Java version for compilation"
    },
    "buildSystem": {
      "visibility": "editable",
      "default": "GRADLE",
      "options": ["GRADLE", "MAVEN", "PROVIDED", "BS2_0", "GOLANG", "IN_CONTAINER"]
    }
  },
  "jira": {
    "releaseVersionFormat": {
      "visibility": "editable",
      "default": "$major02.$minor02.$service02.$fix02",
      "description": "Release version format for Jira"
    }
  }
}
```

### Visibility Modes

| Mode | UI behavior | API behavior |
|------|------------|-------------|
| `editable` | Field shown, user can modify | Accepts value in request; applies default if absent |
| `readonly` | Field shown, grayed out | Ignores value in request; applies server-side value |
| `hidden` | Field not rendered | Ignores value in request; applies default silently |

### Storage

```sql
CREATE TABLE registry_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       JSONB NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Single row for field config:
INSERT INTO registry_config (key, value, updated_by)
VALUES ('field_config', '{ "component": { ... }, "build": { ... } }', 'system');

-- Single row for component defaults (replaces Default.groovy):
INSERT INTO registry_config (key, value, updated_by)
VALUES ('component_defaults', '{
  "system": "CLASSIC",
  "copyright": "Copyright (c) 2026 ACME Corp. All rights reserved.",
  "build": { "javaVersion": "21", "buildSystem": "GRADLE" },
  "escrow": { "reusable": true, "generation": "AUTO" },
  "jira": {
    "releaseVersionFormat": "$major02.$minor02.$service02.$fix02",
    "buildVersionFormat": "$major02.$minor02.$service02.$fix02-$build"
  }
}', 'system');
```

Two separate config entries:
- **`field_config`** — controls UI rendering (visibility, options, descriptions)
- **`component_defaults`** — default values applied when creating a new component (replaces `Default.groovy`)

### API

```
GET    /rest/api/4/admin/config/field-config
PUT    /rest/api/4/admin/config/field-config
  Auth: ADMIN only

GET    /rest/api/4/admin/config/component-defaults
PUT    /rest/api/4/admin/config/component-defaults
  Auth: ADMIN only

GET    /rest/api/4/config/field-config
  Auth: ACCESS_COMPONENTS (read-only, for UI to fetch display rules)
```

### UI Behavior

1. On page load, UI fetches `GET /rest/api/4/config/field-config`
2. For each field, UI checks `visibility`:
   - `editable` — render input
   - `readonly` — render disabled input with value
   - `hidden` — skip rendering
3. When creating a new component, UI pre-fills from `component_defaults`
4. Admin Settings page allows editing both `field_config` and `component_defaults`

### Default Application Logic (Server-Side)

On `POST /rest/api/4/components` (create):
```
1. Load component_defaults
2. Load field_config
3. For each field:
   - If field is "hidden" or "readonly": apply default/server value, ignore request value
   - If field is "editable" and present in request: use request value
   - If field is "editable" and absent in request: apply default
4. Validate merged result
5. Save
```

### Migration from Default.groovy

During initial import:
1. Parse `Default.groovy` into structured JSON
2. Store as `component_defaults` in `registry_config`
3. Generate initial `field_config` with all fields set to `editable`
4. Admin tunes visibility per deployment needs

## Consequences

### Positive
- Each deployment can hide irrelevant fields (e.g., `system` always "CLASSIC") without code changes
- Defaults managed by admin via UI, not by developers via Git commits to Default.groovy
- UI dynamically adapts — no frontend redeployment needed to show/hide fields
- New Tier 3 metadata fields (ADR-010) automatically work — just add to `field_config`
- Clear separation: admin controls what users see, developers control what exists in the model

### Negative
- Extra complexity: UI must fetch config before rendering forms
- Field config itself needs versioning/audit (use the same `audit_log` table)
- Risk of misconfiguration: hiding a required field could break validation — mitigate with server-side validation that ignores visibility

### Interaction with Other ADRs
- **ADR-010 (Schema Extensibility)**: Tier 3 JSONB fields are natural candidates for configurable visibility — adding a new metadata field means adding one entry to `field_config`
- **ADR-005 (Audit Log)**: Changes to `field_config` and `component_defaults` are audited
- **ADR-004 (Keycloak Auth)**: Only ADMIN role can modify field config and defaults

## References
- Current `Default.groovy` in components-registry configuration repository
- Feature flag pattern for per-deployment customization
