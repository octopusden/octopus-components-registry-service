## Purpose

Changes to the component profiles specification for template-only creators: the listing marks
regular profiles unusable, with their own reason, for a user who may create only from templates.

## MODIFIED Requirements

### Requirement: Profiles listed for the current user

`GET /rest/api/4/component-profiles` SHALL return, to a user with `ACCESS_COMPONENTS`, the
live profiles and live templates together, sorted by `order` and then id, each with its id, kind
(`regular` or `template`), title, description, version (for a template only), classification,
its field rules (path, pattern and message), and whether the current user may use it, with the
reason when not. Usability SHALL follow the user's permissions:

| User holds | Regular profile | Template |
|---|---|---|
| `CREATE_COMPONENTS` | usable | usable |
| `CREATE_FROM_TEMPLATE`, without `CREATE_COMPONENTS` | unusable: "You may create components from templates only" | usable |
| neither | unusable: "You do not have permission to create components" | unusable, same reason |

The field rules returned SHALL be the ones a create naming that profile, or a create from that
template, is checked against. The registry checks a pattern with Java regular expressions; a
client checks the same pattern with its own engine, such as JavaScript in the Portal. A pattern
SHALL therefore use syntax both accept; a client that cannot compile a pattern skips its own
check, and the registry's answer on create decides.

#### Scenario: User who may create components
- **WHEN** a user with `CREATE_COMPONENTS` asks
- **THEN** every profile and template is marked usable

#### Scenario: User who may create only from templates
- **WHEN** a user with `CREATE_FROM_TEMPLATE` and without `CREATE_COMPONENTS` asks
- **THEN** every template is marked usable, and every regular profile is marked unusable with the
  reason "You may create components from templates only"

#### Scenario: User with both create permissions
- **WHEN** a user with `CREATE_COMPONENTS` and `CREATE_FROM_TEMPLATE` asks
- **THEN** every profile and template is marked usable

#### Scenario: User who may not create components
- **WHEN** a user with `ACCESS_COMPONENTS` and neither create permission asks
- **THEN** every profile and template is marked unusable with the reason "You do not have
  permission to create components"

#### Scenario: Order ties
- **WHEN** two profiles have the same `order`
- **THEN** they are returned sorted by id

#### Scenario: Failed entries are not listed
- **WHEN** a template entry is failed
- **THEN** it is not returned

#### Scenario: Templates listed with profiles
- **WHEN** a template with order 100 and version 3 is live next to the four regular profiles
- **THEN** it is returned last, with kind `template` and version 3

#### Scenario: Field rules returned
- **WHEN** the Solution profile has a rule on `name`
- **THEN** its entry carries that rule's path, pattern and message, and a profile without rules
  carries an empty rule list

#### Scenario: Rules follow a reload
- **WHEN** a rule's pattern is changed and the reload is applied
- **THEN** the listing returns the new pattern, and a create naming the profile is checked
  against the same new pattern
