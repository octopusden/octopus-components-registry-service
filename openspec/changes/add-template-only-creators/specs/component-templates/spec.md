## Purpose

Changes to the component templates specification for template-only creators: a permission and a
role for users who create only from templates, without overrides, and the regular create kept
from them.

## ADDED Requirements

### Requirement: Template-only creators

The registry SHALL know a permission `CREATE_FROM_TEMPLATE`, and a role
`ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR` granting `ACCESS_COMPONENTS`, `ACCESS_AUDIT` and
`CREATE_FROM_TEMPLATE`, defined in the registry's bundled role map. A user holding
`CREATE_FROM_TEMPLATE` without `CREATE_COMPONENTS` SHALL create components only from a template,
without overrides. `CREATE_COMPONENTS` SHALL keep every right it has, including creating from a
template; a user holding both SHALL have the rights of `CREATE_COMPONENTS`.

#### Scenario: Role grants the permission
- **WHEN** a user's token carries the realm role `COMPONENTS_REGISTRY_TEMPLATE_CREATOR`
- **THEN** `GET /auth/me` lists `ACCESS_COMPONENTS`, `ACCESS_AUDIT` and `CREATE_FROM_TEMPLATE`
  for that role

#### Scenario: Regular create refused
- **WHEN** a user with `CREATE_FROM_TEMPLATE` and without `CREATE_COMPONENTS` calls
  `POST /rest/api/4/components`, with or without a profile
- **THEN** the response is 403 and no component is created

#### Scenario: Editing unchanged
- **WHEN** a user with `CREATE_FROM_TEMPLATE` only is the owner of a component
- **THEN** they may edit it, as any owner may

## MODIFIED Requirements

### Requirement: One endpoint, dry run by default

`POST /rest/api/4/component-templates/{id}/components` SHALL take parameter values, overrides, a
Jira task key and a comment, and a `dryRun` query parameter:

| `dryRun` | What happens |
|---|---|
| Absent or `true` | A dry run: nothing is created |
| `false` | A create from the template |

- A dry run and a create SHALL take the same body and run the same steps; only a create commits.
- Both SHALL require a user who holds `CREATE_COMPONENTS` or `CREATE_FROM_TEMPLATE`, and answer
  403 otherwise.
- A malformed Jira task key SHALL be rejected with 400 on both, so a dry run finds it before the
  create.
- A failed, removed or unknown template SHALL answer 404 on both.
- When several of these fail, the first in this order SHALL answer:

  | Order | Check | Status |
  |---|---|---|
  | 1 | Jira task key pattern | 400 |
  | 2 | User has `ACCESS_COMPONENTS` | 403 |
  | 3 | Template live | 404 |
  | 4 | User may use the template | 403 |
  | 5 | User may override, when the request has overrides | 403 |
  | 6 | Every override path is overridable | 400 |

  Only then do the dry-run steps run.

#### Scenario: Order of the checks
- **WHEN** a request with a malformed Jira task key names an unknown template
- **THEN** the response is 400

#### Scenario: No dryRun means a dry run
- **WHEN** a request without `dryRun` passes every check
- **THEN** the response is 200 with a valid result, and nothing is created

#### Scenario: User who may not create components
- **WHEN** a user with neither `CREATE_COMPONENTS` nor `CREATE_FROM_TEMPLATE` sends a dry run
- **THEN** the response is 403

#### Scenario: User who may create only from templates
- **WHEN** a user with `CREATE_FROM_TEMPLATE` only sends a create without overrides that passes
  every check
- **THEN** the response is 201 with the component

#### Scenario: Same body, dry run then create
- **WHEN** a request passes with `dryRun` absent, and the same body is sent with `dryRun=false`
- **THEN** the second response is 201 with the component, unless something changed in between

### Requirement: Overrides

The registry SHALL accept an override only when:

- its path is in the template's `overridable`; and
- the user holds `CREATE_COMPONENTS`.

An accepted override SHALL still pass every check. An override of a field a parameter could
fill SHALL be checked as that parameter would be: a CRS value or `labels` item SHALL be in its
registry list, a `componentOwner`, `releaseManager` or `securityChampion` login SHALL be an
active employee, and a field that is not a list SHALL take one value.

#### Scenario: Overridable field
- **WHEN** a user with `CREATE_COMPONENTS` overrides `baseConfiguration.vcsEntries[0].vcsPath`,
  which is overridable
- **THEN** the component is created with that value

#### Scenario: Field not overridable
- **WHEN** a request overrides `name`, which is not overridable
- **THEN** the response is 400 naming `name`, and nothing is created

#### Scenario: Override from a template-only creator
- **WHEN** a user with `CREATE_FROM_TEMPLATE` only sends a dry run or create with any override,
  overridable or not
- **THEN** the response is 403, and nothing is created

#### Scenario: Invalid override
- **WHEN** an override makes the component fail a check
- **THEN** the problem is reported on the overridden field

#### Scenario: Label override not in the dictionary
- **WHEN** `labels` is overridable and a request overrides it with a label not in the dictionary
- **THEN** the problem is on `labels`, not a template problem; a create answers 422 and the label
  is not added to the dictionary
