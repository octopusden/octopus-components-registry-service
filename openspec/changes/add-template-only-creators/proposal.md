## Why

- Every user who may create a component may create any component: the regular wizard, every
  profile, every template, and overrides of a template's fields.
- Some users should create components **only** from a template, and change nothing the template
  filled in: they register standard components without knowing every field.
- The registry has no way to tell them apart: there is one create permission,
  `CREATE_COMPONENTS`.
- `add-component-templates` left one seam for this, `ProfileAvailability`: the listing, a create
  naming a profile, and the template endpoints all ask it.

## What Changes

**A second create permission**
- New permission `CREATE_FROM_TEMPLATE`: create a component from a template, with no overrides.
- New role `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR` in the bundled `octopus-security.roles`:
  `ACCESS_COMPONENTS`, `ACCESS_AUDIT`, `CREATE_FROM_TEMPLATE`.
- `CREATE_COMPONENTS` keeps its meaning and includes creating from a template; no existing role
  changes.
- The registry knows roles and permissions only. Which AD group gets which role is Keycloak
  configuration.

**Who may use what** (`PermissionProfileAvailability`)

| User holds | Regular profiles | Templates | Overrides |
|---|---|---|---|
| `CREATE_COMPONENTS` | usable | usable | allowed |
| `CREATE_FROM_TEMPLATE` only | unusable: "You may create components from templates only" | usable | refused |
| neither | unusable: "You do not have permission to create components" | unusable, same reason | refused |

- A user with both permissions keeps every right of `CREATE_COMPONENTS`, which decides first.
- The listing, a create naming a profile, and the template endpoints answer from this one rule.

**Regular create unchanged**
- `POST /rest/api/4/components` still requires `CREATE_COMPONENTS`; a template-only creator
  calling it gets 403, through the API as through the Portal.

## Affected areas

- `components-registry-service-server`:
  - `PermissionProfileAvailability`, `PermissionEvaluator` (the permission name);
  - bundled `application.yml` (the new role).
- **Behavior change, only once a user holds the new role:**
  - the listing marks regular profiles unusable for them, with the new reason;
  - the template endpoints answer 403 to their overrides.
- Nothing changes for a user who holds `CREATE_COMPONENTS` or no create permission.
- Docs: ADR-004 (roles and permissions), `deployment/keycloak-setup.md`, `technical-design.md`,
  `functional-spec.md`, `requirements-common.md`, `api-changelog.md`.

## Out of scope

- **Keycloak**: the new realm role, AD group mappings, and taking `COMPONENTS_REGISTRY_EDITOR`
  out of the default role are a realm change, released after this one.
- **The Portal**: it shows its Create entry only to `CREATE_COMPONENTS` holders, so a template-only
  creator would not reach the start page. Showing it for `CREATE_FROM_TEMPLATE` is a Portal
  change.
- **Editing**: who may edit a component (owner, release manager, security champion, the owner's
  manager, admins) is unchanged; a template-only creator who owns a component edits it as any
  owner does.
- **The technical account holding `ADMIN`**: a realm concern, unrelated to creating from templates.

## Rollout note

- Safe to deploy everywhere at once: no user holds the new role until the realm change, so every
  answer stays as today.
- The realm change must follow this release. Before it, a user given only the new role could not
  create at all.
