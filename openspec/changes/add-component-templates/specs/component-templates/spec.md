## Purpose

Component templates: `kind: template` entries under `components-registry.component-profiles` in
service-config. This specification covers:

- the keys a template accepts, and the checks run when it is loaded;
- describing a template's parameters;
- checking the values a creator submits;
- rendering a template into a create request;
- the dry run, and creating a component from a template;
- overrides;
- the administrator read of every configured entry.

Who counts as Delivery & Support is not covered here.

## ADDED Requirements

### Requirement: Template keys

A template entry SHALL accept only the keys in the tables below.

Template keys:

| Key | Required | Allowed values |
|---|---|---|
| `kind` | Yes | `template` |
| `title`, `description` | Yes | Non-blank text |
| `order` | Yes | Whole number |
| `version` | Yes | Whole number above 0 |
| `classification.external`, `classification.explicit` | Yes | `true`, `false` |
| `classification.solution` | No, default `false` | `true`, `false`; `true` requires `external` and `explicit` both `true` |
| `parameters` | No | Map of parameter name → parameter |
| `fields` | Yes | Map of field path → value |
| `overridable` | No | List of field paths |
| `rules` | No | Field rules, as for a profile |

Parameter keys:

| Key | Applies to | Required | Allowed values |
|---|---|---|---|
| `label` | All | Yes | Non-blank text |
| `hint` | All | No | Text |
| `type` | All | Yes | `text`, `select`, `crs-list`, `person` |
| `required` | All | No, default `true` | `true`, `false` |
| `multiple` | `select`, `person` | No, default `false` | `true`, `false` |
| `options` | `select` | Yes | Non-empty list of distinct texts |
| `max-selection` | `select` with `multiple: true` | No | Whole number above 0 |
| `list` | `crs-list` | Yes | `build-systems`, `escrow-generation`, `labels`; `labels` takes several values, the others one |
| `pattern` | `text` | No | A regular expression that compiles |
| `message` | `text` | No | Text shown when `pattern` fails |
| `max-length` | `text` | No | Whole number above 0 |
| `default` | All | No | A value valid for the parameter; for `person`, a login or `current-user` |

A parameter name SHALL be upper-case letters, digits and `_`, starting with a letter.

Field paths, and the value each field kind accepts:

| Field kind | Paths | Value |
|---|---|---|
| Free text | The field-rule paths of the profiles specification | Text that may contain `{{ NAME }}`, `{{ NAME \| lower }}`, `{{ NAME \| upper }}` |
| CRS value | `baseConfiguration.build.buildSystem`, `baseConfiguration.escrow.generation` | A value of that list, or exactly `{{ NAME }}` |
| Person | `componentOwner` | A login, or exactly `{{ NAME }}` |
| Free-text list | `artifactIds[0].artifactTokens` | Items, each free text or exactly `{{ NAME }}` of a multi-value parameter |
| CRS list | `labels` | Labels, or exactly `{{ NAME }}` of a `labels` parameter |
| People list | `releaseManager`, `securityChampion` | Logins, or exactly `{{ NAME }}` of a `person` parameter |
| Fixed choice | `artifactIds[0].mode`, `baseConfiguration.packages[0].packageType` | A fixed value only |

Expressions are a small subset of Jinja syntax. Only these SHALL be accepted:

| Expression | Meaning |
|---|---|
| `{{ NAME }}` | The parameter's value |
| `{{ NAME \| lower }}` | The value in lower case |
| `{{ NAME \| upper }}` | The value in upper case |

- Spaces inside the braces are optional.
- Any other Jinja construct SHALL fail the template on load: a `{% … %}` tag, a `{# … #}` comment,
  another filter, or an expression other than a parameter name.

There is no `client-codes` list. A template that offers client codes SHALL list them as the
`options` of a `select` parameter.

#### Scenario: Example template
- **WHEN** the configuration holds the design's example template next to valid regular profiles
- **THEN** the template is live

#### Scenario: Unknown key
- **WHEN** a template or one of its parameters has a key not in the tables, such as `maintainer`
- **THEN** the template is failed with a problem naming the key

#### Scenario: Ask is not a template classification
- **WHEN** a template has `classification.explicit: ask`
- **THEN** the template is failed with a problem naming `classification.explicit`

#### Scenario: Jinja tag
- **WHEN** a field holds `{% if REGION %}eu-{% endif %}{{ CLIENT_CODE | lower }}`
- **THEN** the template is failed with a problem naming the field

#### Scenario: Client code list
- **WHEN** a template has a `crs-list` parameter with `list: client-codes`
- **THEN** the template is failed with a problem naming the parameter and `list`

### Requirement: Every template is checked on load

On every load and reload, the registry SHALL check each template:

- When any check below fails, the template SHALL be marked failed.
- Every problem found SHALL be reported, each naming the parameter or field concerned.
- A failed template SHALL NOT be offered.
- A failed template SHALL NOT stop the load or the reload.

Parameter checks:

| Check | Fails when |
|---|---|
| Name and keys | A name, key, type or list is outside the tables, or a key does not apply to the parameter's type, such as `options` on a `text` parameter |
| `multiple` | It is set on a `text` or `crs-list` parameter |
| Options | A `select` has no options, or repeats one |
| Settings | A `pattern` does not compile; `max-length` or `max-selection` is not a whole number above 0 |
| Default | It is not a valid value for the parameter |
| Use | No field uses the parameter |

Field checks:

| Check | Fails when |
|---|---|
| Path | The path is not in the field table |
| Parameter | The field uses a parameter the template does not define |
| Kind | The field kind does not accept the parameter's type: a Person never goes into free text, and a multi-value parameter only into a list |
| Whole value | A CRS value, Person or list item has text around `{{ NAME }}`, or a filter |
| Filter | A filter other than `lower` or `upper` is used |
| Fixed value | A fixed value is not in its static list: build systems, escrow generation modes, fixed choices |

Whole-template checks:

| Check | Fails when |
|---|---|
| Required fields | A field the classification requires has no fixed value, no required parameter and no component default |
| Overridable | An `overridable` path is not set in `fields` |
| Own rules | A fixed value breaks one of the template's rules |

Not checked on load, because they depend on data that changes at runtime:

- Fixed labels; the dry run checks them against the labels dictionary.
- Fixed people; the dry run's create step checks them, as on any create.
- Uniqueness, such as a fixed name already taken; the dry run's create step checks it.

#### Scenario: Key not for the type
- **WHEN** a `text` parameter has `options`
- **THEN** the template is failed with a problem naming the parameter and `options`

#### Scenario: Fixed person not checked on load
- **WHEN** a template fixes `componentOwner` to a login the employee service reports as inactive
- **THEN** the template is live, and the dry run reports the inactive owner as a template problem

#### Scenario: Unused parameter
- **WHEN** a template defines `PLUGIN` and no field uses it
- **THEN** the template is failed with a problem naming `PLUGIN`

#### Scenario: Person in free text
- **WHEN** `displayName` is `"{{ OWNER }} tools"` and `OWNER` is a `person` parameter
- **THEN** the template is failed with a problem naming `displayName` and `OWNER`

#### Scenario: Unknown filter
- **WHEN** a field uses `{{ CLIENT_CODE | capitalize }}`
- **THEN** the template is failed with a problem naming the field

#### Scenario: Required field missing
- **WHEN** an explicit, external template sets no `displayName`, has no required parameter for it,
  and `component-defaults` has no display name
- **THEN** the template is failed with a problem naming `displayName`

#### Scenario: Default not set either
- **WHEN** a template sets no VCS branch for a build system that needs VCS, and
  `component-defaults` has no `vcs.branch`
- **THEN** the template is failed with a problem naming `baseConfiguration.vcsEntries[0].branch`;
  no fallback such as `master` is applied

#### Scenario: No VCS for a build system that needs none
- **WHEN** a template fixes `baseConfiguration.build.buildSystem: PROVIDED` and sets no VCS path
- **THEN** the template is live

#### Scenario: Overridable path not set
- **WHEN** `overridable` lists `baseConfiguration.jira.projectKey` and `fields` does not set it
- **THEN** the template is failed with a problem naming that path

#### Scenario: Broken template next to working ones
- **WHEN** a reload adds a broken template next to a live one
- **THEN** the reload is applied, the broken one is failed with its problems, and the live one
  stays offered

### Requirement: Describe a template's parameters

`GET /rest/api/4/component-templates/{id}` SHALL return a live template to a user with
`ACCESS_COMPONENTS`:

- its id, version, title, description, classification and overridable paths;
- every parameter, in configured order, with its name, label, hint, type, whether it is
  required, its type's settings and its default.

A failed or unknown template SHALL return 404.

#### Scenario: Text parameter
- **WHEN** a `text` parameter has a pattern, message and maximum length
- **THEN** all three are returned with its default

#### Scenario: CRS list parameter
- **WHEN** a parameter is `crs-list` with `list: labels`
- **THEN** it returns the labels list's current values and that several values are allowed

#### Scenario: Current user default
- **WHEN** a `person` parameter defaults to `current-user` and `jdoe` asks
- **THEN** its default is returned as `jdoe`

#### Scenario: Failed template
- **WHEN** the template is failed
- **THEN** the response is 404

### Requirement: Parameter values are checked

The registry SHALL check submitted values against the template's parameters. Every failure SHALL
be reported against its parameter, with a message a creator can act on.

| Check | Applies to | Fails when |
|---|---|---|
| P1 Known parameter | All | A value is given for a parameter the template does not define |
| P2 Required | All | A required parameter is missing or empty |
| P3 Number of values | All | Several values are given for a single-value parameter |
| P4 Maximum length | `text` | The value is longer than `max-length` |
| P5 Pattern | `text` | The value does not match `pattern`; the message is the template's `message` when set |
| P6 Option | `select` | A value is not one of the options |
| P7 List value | `crs-list` | A value is not in its list at the time of the check |
| P8 Active employee | `person` | A login is not an active employee |
| P9 Maximum selection | `select` | More options are selected than `max-selection` |

How values are taken:

- An absent parameter SHALL take its default.
- An empty optional parameter SHALL pass and add nothing.
- A value given twice for a multi-value parameter SHALL count once.
- When the employee service cannot be reached, P8 SHALL pass, as on create.

#### Scenario: Pattern with the template's message
- **WHEN** `PLUGIN_CODE` is `core` against `^[A-Z][A-Z0-9]{2,15}$` with the message
  "3–16 upper-case letters or digits, starting with a letter."
- **THEN** the failure names `PLUGIN_CODE` with that message

#### Scenario: Client code not among the options
- **WHEN** `CLIENT_CODE` is a `select` with options `ACME` and `GLOBEX`, and the value is `XYZ`
- **THEN** P6 fails naming `CLIENT_CODE`

#### Scenario: Label not in the dictionary
- **WHEN** a `labels` parameter is given `nosuchlabel`, which is not in the labels dictionary
- **THEN** P7 fails naming the parameter

#### Scenario: Several failures
- **WHEN** one parameter is missing and another breaks its pattern
- **THEN** both failures are reported

#### Scenario: Inactive person
- **WHEN** `COMPONENT_OWNER` is a login the employee service reports as inactive
- **THEN** P8 fails naming `COMPONENT_OWNER`

### Requirement: Rendering

The registry SHALL turn a template, values that passed the checks, and optional overrides into a
create request. The same input SHALL always give the same request. For each field it sets, the
registry SHALL record the parameters the value came from.

| Rule | What happens |
|---|---|
| R1 Free text | Each expression is replaced by the parameter's value, converted by `lower` or `upper`; the text around it is kept |
| R2 Empty values | An empty optional parameter is replaced by nothing; a free-text field that ends up empty is unset |
| R3 Whole values | A CRS value or Person field set to `{{ NAME }}` takes the value as it is |
| R4 Free-text lists | Each item follows R1 and R2; an empty item is dropped; a multi-value parameter becomes one item per value |
| R5 CRS and people lists | The template's items, then the parameter's values, in order, without duplicates |
| R6 Classification | Always the template's |
| R7 Defaults | A field still unset takes its non-blank value from `component-defaults` (see below) |
| R8 Overrides | An override replaces the rendered value of its field, and nothing else |

R7 SHALL apply only to the fields the Portal's create wizard pre-fills from `component-defaults`:

- build system;
- display name;
- copyright, only for an explicit, external template;
- Jira project key;
- the full, line, minor, release and build version formats;
- escrow generation;
- VCS tag and branch, only when the build system needs VCS.

#### Scenario: Case conversion around fixed text
- **WHEN** `name` is `"{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}"` with `ACME`
  and `CORE`
- **THEN** `name` is `acme-plugin-core`, from `CLIENT_CODE` and `PLUGIN_CODE`

#### Scenario: Empty optional parameter
- **WHEN** an optional `SUFFIX` is empty in `"{{ CLIENT_CODE | lower }}-plugin{{ SUFFIX }}"`
- **THEN** the value is `acme-plugin`

#### Scenario: Labels combine
- **WHEN** the template fixes `labels: [plugin]` and adds `{{ EXTRA }}` with `plugin` and `ui`
- **THEN** `labels` is `plugin`, `ui`

#### Scenario: Default for an unset field
- **WHEN** the template sets no `baseConfiguration.jira.versionFormat` and `component-defaults`
  has one
- **THEN** the rendered request carries the default, with no source parameter

### Requirement: Dry run

`POST /rest/api/4/component-templates/{id}/dry-run` SHALL take parameter values and overrides,
and run these steps:

1. Check the parameter values. When any check fails, stop here.
2. Render the template.
3. Check the template's field rules on the rendered request.
4. Run today's create on the rendered request, in a transaction that is always rolled back.

It SHALL return:

- whether the input is valid;
- every parameter problem;
- the rendered request, and each set field's source parameters;
- every problem found after rendering.

How problems are reported:

- A problem on a field SHALL name the field and its source parameters.
- A problem on a field the template fixes SHALL be marked a template problem.
- A fixed label not in the labels dictionary SHALL be reported as a template problem.
- Today's create stops at its first failure, so at most one problem SHALL come from step 4,
  worded as the create words it.

Nothing SHALL be created, saved or recorded in the audit.

#### Scenario: Parameter problems stop the dry run
- **WHEN** any parameter check fails
- **THEN** the result lists the parameter problems, carries no rendered request, and is not valid

#### Scenario: Key taken
- **WHEN** the rendered `name` is the key of an existing component
- **THEN** the result has a problem on `name` naming `CLIENT_CODE` and `PLUGIN_CODE`, and nothing
  is created

#### Scenario: Field rule
- **WHEN** a template rule on `name` fails for a key built from `PLUGIN_CODE`
- **THEN** the problem carries the rule's message and names `PLUGIN_CODE`

#### Scenario: Rules and the create step both reported
- **WHEN** a field rule fails and today's create would also fail on another field
- **THEN** both problems are reported

#### Scenario: Nothing written
- **WHEN** a dry run passes
- **THEN** no component, label, audit row or other record exists afterwards

#### Scenario: Unknown label in the template
- **WHEN** the template fixes a label that is not in the labels dictionary
- **THEN** the dry run reports a template problem on `labels`

#### Scenario: Failed template
- **WHEN** the template is failed or unknown
- **THEN** the response is 404

### Requirement: Create from a template

`POST /rest/api/4/component-templates/{id}/components` SHALL take parameter values, overrides, a
Jira task key and a comment, and:

- run the dry-run steps at that moment;
- create the component only when no problem is found, with the audit entry any create writes;
- when a problem is found, answer 422 with the dry-run result and create nothing;
- answer 404 when the template is failed, removed or unknown at that moment.

The Jira task key and comment SHALL follow today's create:

- a key that does not match today's pattern SHALL be rejected with 400;
- a blank or absent key SHALL be accepted.

The component SHALL keep no link to the template.

#### Scenario: Created
- **WHEN** every check passes
- **THEN** the response is 201 with the component, and the audit row carries the Jira task key
  and comment

#### Scenario: Problem found
- **WHEN** the dry-run steps find a problem
- **THEN** the response is 422 with the same body a dry run returns, and nothing is created

#### Scenario: Malformed Jira task key
- **WHEN** the request's Jira task key is `not a key`
- **THEN** the response is 400 and nothing is created

#### Scenario: No Jira task key
- **WHEN** the request has no Jira task key and every check passes
- **THEN** the component is created, as any create without a key is

#### Scenario: Template removed meanwhile
- **WHEN** the template was removed by a reload after the creator's dry run
- **THEN** the response is 404

### Requirement: Overrides

The registry SHALL accept an override only when:

- its path is in the template's `overridable`; and
- the availability rule lets the user override.

An accepted override SHALL still pass every check. In this change, every user who may use the
template may override.

#### Scenario: Overridable field
- **WHEN** a user overrides `baseConfiguration.vcsEntries[0].vcsPath`, which is overridable
- **THEN** the component is created with that value

#### Scenario: Field not overridable
- **WHEN** a request overrides `name`, which is not overridable
- **THEN** the response is 400 naming `name`, and nothing is created

#### Scenario: Override not allowed for the user
- **WHEN** the availability rule says the user may not override (not reachable in this change)
- **THEN** the response is 403, and nothing is created

#### Scenario: Invalid override
- **WHEN** an override makes the component fail a check
- **THEN** the problem is reported on the overridden field

### Requirement: Administrator read

`GET /rest/api/4/admin/component-profiles` SHALL return to an administrator:

- every configured profile and template, live or failed, each with:
  - its status and every problem;
  - its definition, when live;
  - its configuration text;
- the configuration version in use, when the config server reports one;
- the outcome of the last load or reload, with its problems when it failed.

#### Scenario: Failed template listed
- **WHEN** an administrator asks and a template is failed
- **THEN** it is included with its problems and its configuration text

#### Scenario: Last reload failed
- **WHEN** the last reload failed on an invalid regular profile
- **THEN** the result says so with the problems, and lists the entries still in use

#### Scenario: Not an administrator
- **WHEN** a user without `IMPORT_DATA` asks
- **THEN** the response is 403
