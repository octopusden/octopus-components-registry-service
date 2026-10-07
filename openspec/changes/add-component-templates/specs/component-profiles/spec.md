## Purpose

Changes to the component profiles specification for templates: a `kind: template` entry is now
read and checked instead of failing, the reload swaps templates with the profiles, and the listing
returns live templates next to the regular profiles.

## MODIFIED Requirements

### Requirement: Profiles are read from configuration

The registry SHALL read every profile from `components-registry.component-profiles`, a map of
profile id → profile.

- A profile id SHALL be lowercase letters, digits and `-`.
- A profile SHALL accept only these keys:

| Key | Required | Allowed values |
|---|---|---|
| `kind` | Yes | `regular` or `template`; a `template` entry has its own keys (component-templates specification) |
| `title` | Yes | Non-blank text |
| `description` | Yes | Non-blank text |
| `order` | Yes | Whole number |
| `classification.external` | Yes | `true`, `false` |
| `classification.explicit` | Yes | `true`, `false`, `ask` |
| `classification.solution` | No, default `false` | `true`, `false`; `true` requires `external: true` and `explicit: true` |
| `rules` | No | Map of field path → field rule |

A field rule SHALL accept only these keys:

| Key | Required | Allowed values |
|---|---|---|
| `pattern` | Yes | A regular expression that compiles; the whole field value must match it. Written in syntax both Java and JavaScript accept (see the listing requirement) |
| `message` | Yes | Non-blank text |

A field rule's path SHALL be one of these create-request paths:

| Area | Paths |
|---|---|
| Component | `name`, `displayName`, `clientCode`, `artifactIds[0].groupPattern` |
| Build | `baseConfiguration.build.buildTasks` |
| VCS | `baseConfiguration.vcsEntries[0].vcsPath`, `.branch`, `.tag` |
| Jira | `baseConfiguration.jira.projectKey`, `.versionPrefix`, `.versionFormat`, `.lineVersionFormat`, `.minorVersionFormat`, `.releaseVersionFormat`, `.buildVersionFormat` |
| Distribution | `baseConfiguration.mavenArtifacts[0].groupPattern`, `.artifactPattern`; `baseConfiguration.dockerImages[0].imageName`, `.flavor`; `baseConfiguration.packages[0].packageName` |

- A path with `[0]` SHALL apply to the first entry of that list only.
- A path SHALL be written as-is in the YAML key, without bracket notation.

The configuration as a whole:

- Each problem SHALL name the profile id and the key concerned.
- The configuration SHALL contain at least one `regular` profile, and every `regular` profile
  SHALL be valid.
- An entry of kind `template` SHALL be read and checked as the component-templates specification
  states; a failed template SHALL NOT affect the rest of the configuration.

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

#### Scenario: Dotted rule path written as-is
- **WHEN** a profile has a rule under the YAML key `baseConfiguration.jira.projectKey`, without
  brackets
- **THEN** the rule is read with the path `baseConfiguration.jira.projectKey`

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
- **THEN** it is read with the template keys, and a failed template leaves the other profiles
  unaffected

#### Scenario: Every problem reported
- **WHEN** one profile lacks `title` and has `kind: special`
- **THEN** both problems are reported

### Requirement: Startup requires usable profiles

The registry SHALL NOT start, and SHALL report every problem found, when the profile subtree:

- cannot be read;
- holds no `regular` profile; or
- holds an invalid `regular` profile.

#### Scenario: No regular profile
- **WHEN** the registry starts and the subtree is absent or holds only template entries
- **THEN** startup fails with a message saying at least one `regular` profile is required

#### Scenario: Invalid regular profile
- **WHEN** the registry starts and one `regular` profile lacks `order`
- **THEN** startup fails with the problem naming that profile and `order`

#### Scenario: Failed template does not block startup
- **WHEN** the registry starts with valid `regular` profiles and a template that fails its load
  checks
- **THEN** the registry starts and that template is not offered

### Requirement: Reload applies usable profiles and keeps the previous ones otherwise

`POST /rest/api/4/admin/reload-config` SHALL re-read the profiles and templates.

- When the result is usable, the registry SHALL use it at once, profiles and templates together.
- When it cannot be read, holds no `regular` profile or holds an invalid `regular` profile, the
  registry SHALL keep the profiles and templates in use unchanged, and answer 422 with
  `error: component-profiles` and the problems.

Either way, the response SHALL carry `componentProfiles` with:

- `status`: `applied` or `failed`;
- `problems`: the configuration-level problems;
- every entry, with its id, kind, status (`live` or `failed`) and problems.

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
- **WHEN** a reload adds a valid template and a broken one next to valid `regular` profiles
- **THEN** the reload is applied, the valid template is listed as `live` and offered, and the broken
  one is listed as `failed` with its problems

#### Scenario: Templates kept with the profiles
- **WHEN** a reload changes a template and makes a `regular` profile invalid
- **THEN** the reload is not applied and the previous template stays in use

#### Scenario: Fixed and reloaded
- **WHEN** a reload failed and the configuration is corrected and reloaded
- **THEN** the reload is applied

### Requirement: Profiles listed for the current user

`GET /rest/api/4/component-profiles` SHALL return, to a user with `ACCESS_COMPONENTS`, the live
profiles and live templates together, sorted by `order` and then id. Each entry SHALL carry:

- id, kind (`regular` or `template`), title, description;
- version, for a template only;
- classification;
- its field rules: path, pattern and message;
- whether the current user may use it, with the reason when not.

Rules and availability:

- The field rules returned SHALL be the ones a create naming that profile, or a create from that
  template, is checked against.
- A template SHALL be usable by a user who holds `CREATE_COMPONENTS`, as a regular profile is.
- The registry checks a pattern with Java regular expressions; a client checks it with its own
  engine, such as JavaScript in the Portal. A pattern SHALL therefore use syntax both accept.
- A client that cannot compile a pattern skips its own check; the registry's answer on create
  decides.

#### Scenario: User who may create components
- **WHEN** a user with `CREATE_COMPONENTS` asks
- **THEN** every profile and template is marked usable

#### Scenario: User who may not create components
- **WHEN** a user with `ACCESS_COMPONENTS` but without `CREATE_COMPONENTS` asks
- **THEN** every profile is marked unusable with the reason "You do not have permission to create
  components"

#### Scenario: Order ties
- **WHEN** two profiles have the same `order`
- **THEN** they are returned sorted by id

#### Scenario: Templates listed with profiles
- **WHEN** a template with order 100 and version 3 is live next to the four regular profiles
- **THEN** it is returned last, with kind `template` and version 3

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
