## Purpose

Stores, validates and exposes where each VCS entry's sources are placed and where the build runs,
so build automation can check out multi-root and shared-repository components from registry data
alone.

## ADDED Requirements

### Requirement: Placement fields on VCS entries

The registry SHALL accept `sourcePath` and `checkoutDirectory` on each v4 VCS entry, store them per
configuration row, and return them in v4 responses and in the legacy v2 VCS settings, omitting a
field that is empty. A blank value (empty or whitespace only) in a request SHALL be treated as
absent.

#### Scenario: v4 round-trip
- **WHEN** a component is updated with a first entry carrying `checkoutDirectory: "feature"` and a
  second entry carrying `sourcePath: "data"` and no `checkoutDirectory`
- **THEN** the v4 component response returns these values for those entries

#### Scenario: Blank value is absent
- **WHEN** a single entry is saved with `checkoutDirectory: ""` and `sourcePath: " "`
- **THEN** the write is not rejected for those fields and the entry is stored and returned without
  either field

#### Scenario: v2 exposure
- **WHEN** the v2 VCS settings are requested for a version covered by that configuration
- **THEN** the entry carries `sourcePath` and `checkoutDirectory` with the same values

#### Scenario: Unplaced entries unchanged in v2
- **WHEN** a component has no placement data and no Build Working Directory
- **THEN** its v2 VCS settings JSON is identical to the response before this change

### Requirement: Placement validation

The registry SHALL reject a v4 write that replaces VCS entries of any configuration row, base or
per-range, with a 400 whose `errorMessage` starts with `vcsEntries[<i>].<field>: ` (preceded by
`fieldOverrides[<j>].` when the row is sent in a component PATCH's `fieldOverrides`), when the row:
has more than one entry without `checkoutDirectory`, reported on the later one; has a
`checkoutDirectory` that does not match `^[A-Za-z0-9_][A-Za-z0-9._-]*$`, is longer than 255
characters, or is `report-templates`, `sonar-config`, `target` or `sonar-report` ignoring case;
would get two entries with the same derived name, compared case-insensitively, reported on the
later entry's `checkoutDirectory`; has a `sourcePath` longer than 255 characters or with a segment
that is empty, `.`, `..` or does not match `^[A-Za-z0-9._-]+$` (which excludes absolute paths); or
has two entries with the same repository and `sourcePath`, reported on the later entry's
`sourcePath`.

#### Scenario: Checkout Directory on the first entry
- **WHEN** a write sets `checkoutDirectory: "core"` on the first entry of a row with a second
  entry at the checkout root, or on a single entry together with `buildWorkingDirectory: "core"`
- **THEN** the write succeeds

#### Scenario: Two entries at the checkout root
- **WHEN** a write leaves a row with two entries and neither has a `checkoutDirectory`
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[1].checkoutDirectory: `

#### Scenario: Every entry in a Checkout Directory
- **WHEN** a write leaves a row with entries in `core` and `feature` and
  `buildWorkingDirectory: "core"`
- **THEN** the write succeeds

#### Scenario: Checkout Directory equal to the name of the entry at the checkout root
- **WHEN** a write leaves a row whose first entry has no `checkoutDirectory` and is named `main`,
  and whose second entry has `checkoutDirectory: "Main"`
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[1].checkoutDirectory: `

#### Scenario: Two entries with the same Checkout Directory
- **WHEN** a write leaves a row with three entries whose second and third have
  `checkoutDirectory` `feature` and `FEATURE`
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[2].checkoutDirectory: `

#### Scenario: Per-range row validated too
- **WHEN** a field-override write sets a VCS marker row to two entries, neither with a
  `checkoutDirectory`
- **THEN** the write fails with 400 naming the second entry's `checkoutDirectory`

#### Scenario: Leading dot
- **WHEN** a write sets an entry's `checkoutDirectory` to `.hidden`
- **THEN** the write fails with 400 naming that field

#### Scenario: Nested checkout directory
- **WHEN** a write sets an entry's `checkoutDirectory` to `a/b`
- **THEN** the write fails with 400 naming that field

#### Scenario: Escaping source path
- **WHEN** a write sets `sourcePath` to `../other`, `/abs` or `a b`
- **THEN** the write fails with 400 naming that field

#### Scenario: Reserved name
- **WHEN** a write sets an entry's `checkoutDirectory` to `report-templates` or `Target`
- **THEN** the write fails with 400 naming that field

#### Scenario: Same repository and path twice
- **WHEN** entries 0 and 2 of one row have the same repository (ignoring case for Git), the same
  `sourcePath` and different Checkout Directories
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[2].sourcePath: `

#### Scenario: Marker-row error in a combined PATCH
- **WHEN** a component PATCH carries `fieldOverrides` whose second entry is a VCS marker row with
  two entries, neither with a `checkoutDirectory`
- **THEN** the response is 400 with `errorMessage` starting
  `fieldOverrides[1].vcsEntries[1].checkoutDirectory: `

### Requirement: Build Working Directory

The registry SHALL accept an optional `buildWorkingDirectory` on the base configuration and on
per-range VCS rows, store it on that row, return it in v4 responses, and return it in the legacy
v2 VCS settings of every version whose VCS entries come from that row, omitting it when empty. A
blank value SHALL be treated as absent. It SHALL be rejected with a 400 whose `errorMessage`
starts with `buildWorkingDirectory: ` (preceded by `fieldOverrides[<j>].` for a row in a component
PATCH's `fieldOverrides`) when it is longer than 255 characters, when a segment is empty, `.`,
`..` or does not match `^[A-Za-z0-9._-]+$`, or when its first segment equals no entry's
`checkoutDirectory` (compared case-sensitively) and every entry of the row has one. When the row has entries and every one
has a `checkoutDirectory`, an absent `buildWorkingDirectory` SHALL be rejected the same way
(`buildWorkingDirectory: required when every VCS entry has a Checkout Directory`). It SHALL be validated whenever the row's
VCS entries or its Build Working Directory are written.

#### Scenario: Inside a placed entry
- **WHEN** a row with entries in `core` and `feature` is saved with
  `buildWorkingDirectory: "core/mapper"`
- **THEN** the write succeeds and v4 and v2 return `core/mapper`

#### Scenario: Below the checkout root
- **WHEN** a row whose second entry has no `checkoutDirectory` is saved with
  `buildWorkingDirectory: "mapper"`
- **THEN** the write succeeds

#### Scenario: Outside every placed entry
- **WHEN** a row with entries in `core` and `feature` is saved with
  `buildWorkingDirectory: "other"`
- **THEN** the write fails with 400 and `errorMessage` starting `buildWorkingDirectory: `

#### Scenario: Every entry in a Checkout Directory without a Build Working Directory
- **WHEN** a row is saved with entries in `core` and `feature` and no `buildWorkingDirectory`
- **THEN** the write fails with 400 and `errorMessage` starting
  `buildWorkingDirectory: required when every VCS entry has a Checkout Directory`

#### Scenario: Missing value on a per-range row in a combined PATCH
- **WHEN** a component PATCH carries `fieldOverrides` whose second entry is a VCS marker row with
  entries in `core` and `feature` and no `buildWorkingDirectory`
- **THEN** the response is 400 with `errorMessage` starting
  `fieldOverrides[1].buildWorkingDirectory: required`

#### Scenario: Entries changed under a stored value
- **WHEN** a row stores `buildWorkingDirectory: "core"` and a write changes its entries so that no
  entry is in `core` and each has a Checkout Directory
- **THEN** the write fails with 400 and `errorMessage` starting `buildWorkingDirectory: `

#### Scenario: Escaping path
- **WHEN** a row is saved with `buildWorkingDirectory` `../x` or `/abs`
- **THEN** the write fails with 400 naming `buildWorkingDirectory`

#### Scenario: Per-range row in a combined PATCH
- **WHEN** a component PATCH carries `fieldOverrides` whose first entry is a VCS marker row with
  entries in `core` and `feature` and `buildWorkingDirectory: "other"`
- **THEN** the response is 400 with `errorMessage` starting
  `fieldOverrides[0].buildWorkingDirectory: `

#### Scenario: Per-range value reaches v2
- **WHEN** a VCS marker row for range `[2,3)` has `buildWorkingDirectory: "core"` and the base row
  has none
- **THEN** the v2 VCS settings of version `2.1` carry `buildWorkingDirectory` `core` and those of a
  version outside the range carry none

#### Scenario: Cleared
- **WHEN** a base PATCH sends `buildWorkingDirectory: ""` for a row that has an entry at the
  checkout root and stores `mapper`
- **THEN** the row no longer has a Build Working Directory, and a PATCH without the field leaves a
  stored value unchanged

### Requirement: Derived name

The registry SHALL ignore `name` in v4 requests. It SHALL store as the `name` of an entry with a
`checkoutDirectory` that directory, and as the `name` of an entry without one the stored name of the
row's previous entry with the same repository (Git compared case-insensitively; the lowest
previous position when the repository appeared more than once), otherwise `main`.

#### Scenario: Name equals checkout directory
- **WHEN** an entry is saved with `checkoutDirectory: "feature"` and `name: "other"`, next to an
  entry at the checkout root
- **THEN** the stored and returned name is `feature`, in v4 and in the v2 VCS settings

#### Scenario: Entry at the checkout root keeps its name
- **WHEN** a single-entry row named `core` on repository R is saved with R still at the checkout
  root and a second entry with `checkoutDirectory: "feature"`
- **THEN** the first entry's name stays `core` and the second's is `feature`

#### Scenario: Entry at the checkout root listed second
- **WHEN** a row with entries `app` (repository A, no Checkout Directory) and `gateway`
  (repository G, Checkout Directory `gateway`) is saved with A in Checkout Directory `app` and G
  without Checkout Directory
- **THEN** A is named `app` and G keeps the name `gateway`

#### Scenario: Re-pointed to another repository
- **WHEN** a row's entry `core` (repository R, no Checkout Directory) is saved with repository S
  instead and no Checkout Directory
- **THEN** the entry's name is `main`

#### Scenario: Repository compared ignoring case
- **WHEN** an entry named `core` on Git repository `ssh://host/Proj/Repo.git` is saved without
  Checkout Directory as `ssh://host/proj/repo.git`
- **THEN** its name stays `core`

#### Scenario: Two entries reduced to one
- **WHEN** a row with entries `alpha` (no Checkout Directory) and `beta` is saved with only the
  `alpha` entry
- **THEN** the name is `alpha`

#### Scenario: Emptied row refilled
- **WHEN** a row with entries `alpha` and `beta` is saved with no entries, and then saved with one
  entry without Checkout Directory
- **THEN** the empty save succeeds, and the new entry's name is `main`

#### Scenario: New unplaced entry
- **WHEN** a component is created with one entry without `checkoutDirectory`
- **THEN** its name is `main`

### Requirement: Migration of existing rows

The schema migration `V8__` SHALL set `checkoutDirectory` to `name` for every entry after the first
(`sort_order > 0`) of every configuration row that has more than one entry, SHALL leave the first
entry and single entries without placement, and SHALL not fail on names that are not valid
Checkout Directories or not distinct. `V9__` SHALL add a nullable Build Working Directory to
configuration rows without changing data. The DSL import SHALL apply `V8__`'s rule to the rows it
creates.

#### Scenario: Migrated rows
- **WHEN** the migrations run on a database with a two-entry row named `alpha`/`beta` and a
  single-entry row
- **THEN** `beta` has `checkoutDirectory` `beta`, `alpha` and the single entry have none, and no
  row has a Build Working Directory

#### Scenario: Migrated row with unusable names
- **WHEN** the migrations run on a two-entry row whose names are both `main`, and on one whose
  second name is `a/b`
- **THEN** the migrations succeed; a later unchanged v4 save of such a row fails with 400 naming
  `vcsEntries[1].checkoutDirectory`

#### Scenario: V9 on a database migrated to V8
- **WHEN** `V9__` runs on a database where `V8__` was applied earlier
- **THEN** it succeeds and every entry keeps its placement

#### Scenario: Imported multi-root component
- **WHEN** the DSL import creates a row with roots `alpha` and `beta`
- **THEN** `beta` has `checkoutDirectory` `beta`, `alpha` has none, and an unchanged v4 save of the
  row succeeds

### Requirement: Chain-mismatch warning

The registry SHALL include in the v4 component detail response to a successful write a warning that
the TeamCity build chain must be recreated, when the request carries base-configuration
`vcsEntries` or `buildWorkingDirectory` and the component has a linked TeamCity project;
`warnings` SHALL be an empty list otherwise, including on GET. Writes of VCS marker rows (per-range
overrides) SHALL not warn.

#### Scenario: Entry added with linked project
- **WHEN** an entry is added to a component with a linked TeamCity project
- **THEN** the response `warnings` contains the chain-mismatch warning

#### Scenario: Build Working Directory changed with linked project
- **WHEN** only the base `buildWorkingDirectory` of a component with a linked TeamCity project
  changes
- **THEN** the response `warnings` contains the chain-mismatch warning

#### Scenario: No linked project
- **WHEN** VCS entries are written for a component without a linked TeamCity project
- **THEN** the response `warnings` is empty

#### Scenario: Unrelated edit
- **WHEN** only the component's display name changes
- **THEN** the response `warnings` is empty

#### Scenario: Marker-row write
- **WHEN** a VCS marker row of a component with a linked TeamCity project is written, through the
  field-override endpoints or the `fieldOverrides` of a component PATCH that carries no base
  `vcsEntries` or `buildWorkingDirectory`
- **THEN** no chain-mismatch warning is returned

### Requirement: Compatible v2 DTOs

`VersionControlSystemRootDTO` SHALL keep a six-parameter JVM constructor and the order of its
first six properties, and `VCSSettingsDTO` a two-parameter JVM constructor and the order of its
first two properties, with the new properties appended.

#### Scenario: Positional construction and destructuring
- **WHEN** Kotlin code constructs `VersionControlSystemRootDTO` with the previous six arguments and
  destructures its first two components
- **THEN** it compiles and yields `name` and `vcsPath`

#### Scenario: Previous constructors from Groovy
- **WHEN** Groovy code calls `new VersionControlSystemRootDTO(...)` with the previous six arguments
  and `new VCSSettingsDTO(...)` with the previous two
- **THEN** it compiles and runs, with the new properties null
