## Context

**How a user gets permissions**
- Keycloak returns the user's realm roles on userinfo; cloud-commons turns each into `ROLE_<name>`.
- `octopus-security.roles` maps each role to permissions; `PermissionEvaluator.hasPermission`
  checks the union. A role missing from the map grants nothing.
- `GET /auth/me` returns the user's roles with their permissions; the Portal reads it.
- Bundled roles today: `ROLE_ANONYMOUS`, `ROLE_COMPONENTS_REGISTRY_VIEWER`,
  `ROLE_COMPONENTS_REGISTRY_EDITOR` (`CREATE_COMPONENTS`), `ROLE_ADMIN`. service-config overrides
  `ROLE_ADMIN` only.

**Who may create** (from `add-component-profiles` and `add-component-templates`)
- `ProfileAvailability` answers three questions: may the user use a profile, use a template,
  override a template's fields.
- Asked by:
  - `ComponentProfileControllerV4` — the listing's `usable` and `reason`;
  - `ProfileCreateCheck` — a create naming a profile (403 with the reason);
  - `ComponentTemplateControllerV4` — dry run and create (403), overrides (403);
  - `AdminControllerV4` — the profile entries of the administrator read.
- `PermissionProfileAvailability`, the only implementation, answers all three with
  `CREATE_COMPONENTS`.
- `POST /rest/api/4/components` requires `CREATE_COMPONENTS` in `@PreAuthorize`, before any profile
  check.

## Goals / Non-Goals

**Goals:**
- A user with `CREATE_FROM_TEMPLATE` only creates from templates, without overrides, in the Portal
  and through the API.
- A user with `CREATE_COMPONENTS` keeps every right, whatever else they hold.
- No change for any user until the realm grants the new role.

**Non-Goals:**
- Knowing AD groups: the realm maps groups to roles.
- Changing who may edit, rename, archive or delete.
- The Portal's Create entry.

## Decisions

### 1. A permission, not a group check

- The registry gains a permission, `CREATE_FROM_TEMPLATE`, and a role carrying it,
  `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR`, in the bundled `octopus-security.roles`:
  `ACCESS_COMPONENTS`, `ACCESS_AUDIT`, `CREATE_FROM_TEMPLATE`.
- AD groups reach the registry as `GROUP_*` authorities, but the registry never reads them: every
  rule is a permission, as ADR-004 sets out. The realm maps AD groups to roles.
- The role lives in the bundled `application.yml`, beside the other registry roles, so every
  environment knows it without a service-config change.

### 2. `CREATE_COMPONENTS` includes creating from a template

- A template is usable with `CREATE_COMPONENTS` or `CREATE_FROM_TEMPLATE`.
- `ROLE_COMPONENTS_REGISTRY_EDITOR` and `ROLE_ADMIN` stay as they are; neither needs the new
  permission.
- Why: the permissions nest (template-only ⊂ any create), so the broader one implies the narrower.
  Listing both on every creating role would add config that can drift, with no case where a user
  may create any component but not from a template.

### 3. One rule, broadest permission first

- `PermissionProfileAvailability`:

  | Question | `CREATE_COMPONENTS` | `CREATE_FROM_TEMPLATE` only | Neither |
  |---|---|---|---|
  | Regular profile | usable | unusable, template-only reason | unusable, no-permission reason |
  | Template | usable | usable | unusable, no-permission reason |
  | Override | yes | no | no |

- `CREATE_COMPONENTS` is checked first, so a user holding both keeps every right of
  `CREATE_COMPONENTS`.
- Reasons:
  - template-only: "You may create components from templates only";
  - no permission: "You do not have permission to create components", unchanged.
- `CREATE_FROM_TEMPLATE` joins the permission names in `PermissionEvaluator`'s companion;
  `PermissionProfileAvailability` reads both names from there instead of keeping its own copy.

### 4. The regular create endpoint keeps its guard

- `POST /rest/api/4/components` keeps `@PreAuthorize(ACCESS_COMPONENTS and CREATE_COMPONENTS)`.
- A template-only creator gets 403 from it with or without a profile, before `ProfileCreateCheck`
  runs. The template-only reason therefore reaches them through the listing, not through this 403.
- Why not drop the guard and let `ProfileCreateCheck` answer: a create without a profile has no
  availability check, so the guard is what stops it.

### 5. The template endpoints keep their order of checks

- Still `ACCESS_COMPONENTS` in `@PreAuthorize`; usability, then override rights, inside, as in
  `add-component-templates` (order 4 and 5).
- A request with overrides from a template-only creator answers 403 at step 5, before the
  override paths are checked.

## Out of Scope

- **The realm change**: the role, the group mappings, and replacing `COMPONENTS_REGISTRY_EDITOR`
  with `COMPONENTS_REGISTRY_VIEWER` in the default role. Released after this change.
- **The Portal**: it reads `CREATE_COMPONENTS` from `/auth/me` to show Create, Copy and the palette
  action; showing Create for `CREATE_FROM_TEMPLATE` is a Portal change. Copy stays
  `CREATE_COMPONENTS`, since it opens the regular wizard.

## Risks / Trade-offs

- **Accounts relying on the default role.** Once the realm moves `COMPONENTS_REGISTRY_EDITOR` out
  of the default role, every user and technical account not given `COMPONENTS_REGISTRY_EDITOR` loses
  `CREATE_COMPONENTS`. Mitigated in the realm change: technical accounts get the role directly,
  and the group given `COMPONENTS_REGISTRY_EDITOR` is checked before the switch.
- **Portal lag.** Between the realm change and the Portal change, a template-only creator has
  the right but no Create entry. The realm change should wait for the Portal.
- **The reason is server text.** The Portal shows it as given, as it does today's reason; wording
  changes need a registry release.
