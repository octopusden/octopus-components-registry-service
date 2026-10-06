## Purpose

Component profiles configured in service-config under `components-registry.component-profiles`:
how they are read and checked at startup and on reload, how they are listed for the current
user, and the optional profile on `POST /rest/api/4/components` with its field rules. Field
rules apply only to a create that names a profile — not to renames, solution-flag changes or
creates without a profile. Templates are not covered here.

## ADDED Requirements

### Requirement: Profiles are read from configuration

The registry SHALL read every profile from `components-registry.component-profiles`, a map of
profile id → profile. A profile id SHALL consist of lowercase letters, digits and `-`. A profile
SHALL accept only these keys, with these values:

| Key | Required | Allowed values |
|---|---|---|
| `kind` | Yes | `regular` or `template`; a `template` entry is not supported yet (see below) |
| `title` | Yes | Non-blank text |
| `description` | Yes | Non-blank text |
| `order` | Yes | Whole number |
| `classification.external` | Yes | `true`, `false` |
| `classification.explicit` | Yes | `true`, `false`, `ask` |
| `classification.solution` | No, default `false` | `true`, `false`; `true` requires `external: true` and `explicit: true` |
| `rules` | No | Map of field path → field rule |

A field rule SHALL accept only these keys, with these values:

| Key | Required | Allowed values |
|---|---|---|
| `pattern` | Yes | A regular expression that compiles; the whole field value must match it |
| `message` | Yes | Non-blank text |

A field rule's path SHALL be one of these create-request paths: `name`, `displayName`,
`clientCode`, `artifactIds[0].groupPattern`, `baseConfiguration.build.buildTasks`,
`baseConfiguration.vcsEntries[0].vcsPath`, `baseConfiguration.vcsEntries[0].branch`,
`baseConfiguration.vcsEntries[0].tag`, `baseConfiguration.jira.projectKey`,
`baseConfiguration.jira.versionPrefix`, `baseConfiguration.jira.versionFormat`,
`baseConfiguration.jira.lineVersionFormat`, `baseConfiguration.jira.minorVersionFormat`,
`baseConfiguration.jira.releaseVersionFormat`, `baseConfiguration.jira.buildVersionFormat`,
`baseConfiguration.mavenArtifacts[0].groupPattern`,
`baseConfiguration.mavenArtifacts[0].artifactPattern`,
`baseConfiguration.dockerImages[0].imageName`, `baseConfiguration.dockerImages[0].flavor`,
`baseConfiguration.packages[0].packageName`.

Each problem SHALL name the profile id and the key concerned. The configuration SHALL contain
at least one `regular` profile, and every `regular` profile SHALL be valid. An entry of kind
`template` SHALL be marked failed with the problem "templates are not supported yet", SHALL NOT be
checked further, and SHALL NOT affect the rest of the configuration.

#### Scenario: Valid regular profiles only
- **WHEN** the subtree holds four valid `regular` profiles and nothing else
- **THEN** all four are live

#### Scenario: Missing required key
- **WHEN** a profile lacks any one of `kind`, `title`, `description`, `order`,
  `classification.external` or `classification.explicit`
- **THEN** that profile is invalid with a problem naming the missing key

#### Scenario: Optional solution flag
- **WHEN** a profile has no `classification.solution`
- **THEN** it is valid and classified as not a solution

#### Scenario: Unknown key
- **WHEN** a profile, its `classification` or one of its field rules has a key not in the tables
  above, such as `maintainer`
- **THEN** that profile is invalid with a problem naming the key

#### Scenario: Value outside the allowed values
- **WHEN** a profile has `kind: special`, `title: ""`, `order: ten`,
  `classification.external: yes` or `classification.explicit: maybe`
- **THEN** that profile is invalid with a problem naming the key and the value

#### Scenario: Invalid profile id
- **WHEN** a profile id contains an upper-case letter, `_` or a space
- **THEN** that profile is invalid with a problem naming the id

#### Scenario: Solution profile not explicit and external
- **WHEN** a profile has `classification.solution: true` with `classification.explicit: ask`,
  `classification.explicit: false` or `classification.external: false`
- **THEN** that profile is invalid

#### Scenario: Invalid field rule
- **WHEN** a field rule has a pattern that does not compile, a missing or blank `message`, a
  missing `pattern`, or a path not in the list above
- **THEN** that profile is invalid with a problem naming the rule's path

#### Scenario: Template entry
- **WHEN** an entry has `kind: template`
- **THEN** it is failed with the problem "templates are not supported yet" and the other profiles
  are unaffected

#### Scenario: Every problem reported
- **WHEN** one profile lacks `title` and has `kind: special`
- **THEN** both problems are reported

### Requirement: Startup requires usable profiles

The registry SHALL NOT start when the profile subtree cannot be read, holds no `regular` profile,
or holds an invalid `regular` profile, and SHALL report every problem found.

#### Scenario: No regular profile
- **WHEN** the registry starts and the subtree is absent or holds only template entries
- **THEN** startup fails with a message saying at least one `regular` profile is required

#### Scenario: Invalid regular profile
- **WHEN** the registry starts and one `regular` profile lacks `order`
- **THEN** startup fails with the problem naming that profile and `order`

#### Scenario: Failed template does not block startup
- **WHEN** the registry starts with valid `regular` profiles and a template entry
- **THEN** the registry starts and the template is not offered

### Requirement: Reload applies usable profiles and keeps the previous ones otherwise

`POST /rest/api/4/admin/reload-config` SHALL re-read the profiles. When the result is usable the
registry SHALL use it at once; when it cannot be read, holds no `regular` profile or holds an
invalid `regular` profile, the registry SHALL keep the profiles in use unchanged and answer 422
with `error: component-profiles` and the problems. Either way the response SHALL carry
`componentProfiles` with `status` (`applied` or `failed`), configuration-level `problems`, and
every entry with its id, kind, status (`live` or `failed`) and problems.

#### Scenario: Valid change
- **WHEN** a profile's title is changed in service-config and the configuration is reloaded
- **THEN** the response has `componentProfiles.status: applied` and the listing returns the new
  title without a restart

#### Scenario: Profile removed
- **WHEN** a profile is removed in service-config and the configuration is reloaded
- **THEN** the listing no longer returns it and a create naming it is rejected as unknown

#### Scenario: Invalid regular profile among valid changes
- **WHEN** a reload changes one profile's title and makes another `regular` profile invalid
- **THEN** the response is 422 with `componentProfiles.status: failed`, the invalid profile is
  listed with its problems, and the listing and creates keep using the previous profiles,
  including the previous title

#### Scenario: Template entry on reload
- **WHEN** a reload adds a template entry next to valid `regular` profiles
- **THEN** the reload is applied and the entry is listed in the response as `failed`

#### Scenario: Fixed and reloaded
- **WHEN** a reload failed and the configuration is corrected and reloaded
- **THEN** the reload is applied

### Requirement: Profiles listed for the current user

`GET /rest/api/4/component-profiles` SHALL return, to a user with `ACCESS_COMPONENTS`, the
live profiles sorted by `order` and then id, each with its id, kind, title, description,
classification, its field rules (path, pattern and message), and whether the current user may
use it, with the reason when not. The field rules returned SHALL be the ones a create naming that
profile is checked against.

#### Scenario: User who may create components
- **WHEN** a user with `CREATE_COMPONENTS` asks
- **THEN** every profile is marked usable

#### Scenario: User who may not create components
- **WHEN** a user with `ACCESS_COMPONENTS` but without `CREATE_COMPONENTS` asks
- **THEN** every profile is marked unusable with the reason "You do not have permission to create
  components"

#### Scenario: Order ties
- **WHEN** two profiles have the same `order`
- **THEN** they are returned sorted by id

#### Scenario: Failed entries are not listed
- **WHEN** a template entry is failed
- **THEN** it is not returned

#### Scenario: Field rules returned
- **WHEN** the Solution profile has a rule on `name`
- **THEN** its entry carries that rule's path, pattern and message, and a profile without rules
  carries an empty rule list

#### Scenario: Rules follow a reload
- **WHEN** a rule's pattern is changed and the reload is applied
- **THEN** the listing returns the new pattern, and a create naming the profile is checked
  against the same new pattern

### Requirement: Optional profile on create

`ComponentCreateRequest` SHALL accept an optional `profile`; the rest of the request and the
endpoint are unchanged. When `profile` is given, the registry SHALL reject the create unless it
names a live `regular` profile (400, `profile: `), the user may use it (403, with the reason),
and the request's classification matches it (400, `profile: `, naming the differing flag).
`explicit: ask` SHALL match either value; an absent request flag SHALL count as `false`. When
`profile` is absent, no field rules SHALL apply.

#### Scenario: Unknown profile
- **WHEN** a create names `profile: nightly`, which is not configured
- **THEN** the response is 400 with `errorMessage` starting `profile: ` and no component is created

#### Scenario: Classification differs
- **WHEN** a create names `profile: solution` with `solution: false`
- **THEN** the response is 400 with `errorMessage` starting `profile: ` naming `solution`

#### Scenario: Explicit asked
- **WHEN** a create names `profile: regular-external` with `distributionExternal: true` and
  `distributionExplicit: false`
- **THEN** the classification check passes

#### Scenario: No profile
- **WHEN** a create names no profile
- **THEN** the create is checked as today, with no field rules, even for a key such as
  `resolution-service` or a solution without `solution` in its key

### Requirement: Field rules of the chosen profile

When a create names a profile, the registry SHALL check the value at each rule's path against
the rule's pattern, as a whole-value match, treating an absent value as empty. A failure SHALL be
400 with `errorMessage` starting with the rule's path and `: `, followed by the rule's message.
Rules in force at the time of the create SHALL apply; existing components SHALL NOT be re-checked.

#### Scenario: Solution key rule
- **WHEN** a create names `profile: solution` with `name: payments-dmp-bundle`
- **THEN** the response is 400 with `errorMessage` `name: A solution key contains -solution, e.g.
  payments-solution.`

#### Scenario: Regular profile keeps solution words out
- **WHEN** a create names `profile: regular-internal` with `name: resolution-service` and a
  matching classification, and that profile has the rule
  `name: { pattern: "^(?!.*(solution|dmp-bundle)).*$", message: "A regular component's key cannot contain solution or dmp-bundle. Choose the Solution or DMP Bundle profile." }`
- **THEN** the response is 400 with `errorMessage` starting `name: A regular component's key
  cannot contain solution or dmp-bundle.`

#### Scenario: DMP Bundle key rule
- **WHEN** a create names `profile: dmp-bundle` with `name: payments-dmp-bundle` and a matching
  classification
- **THEN** the component is created

#### Scenario: Field without a rule
- **WHEN** the profile has no rule for `displayName`
- **THEN** `displayName` is checked only by the existing create validation

#### Scenario: Absent value
- **WHEN** a profile has a rule on `clientCode` whose pattern does not match an empty value, and
  the create has no `clientCode`
- **THEN** the response is 400 with `errorMessage` starting `clientCode: `

#### Scenario: Existing components not re-checked
- **WHEN** the Solution profile's `name` pattern is changed and reloaded
- **THEN** existing components keep their keys without error

#### Scenario: Rename not checked
- **WHEN** a component created with `profile: regular-internal` is renamed to `payments-solution`
- **THEN** the rename is checked as today; no profile rule applies
