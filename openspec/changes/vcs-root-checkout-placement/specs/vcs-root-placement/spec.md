## Purpose

Stores, validates and exposes where each VCS entry's sources are placed, so build automation can
check out multi-root and shared-repository components from registry data alone.

## ADDED Requirements

### Requirement: Placement fields on VCS entries

The registry SHALL accept `sourcePath` and `checkoutDirectory` on each v4 VCS entry, store them per
configuration row, and return them in v4 responses and in the legacy v2 VCS settings, omitting a
field that is empty. A blank value (empty or whitespace only) in a request SHALL be treated as
absent.

#### Scenario: v4 round-trip
- **WHEN** a component is updated with an entry carrying `sourcePath: "mapper"` and
  `checkoutDirectory: "core"`
- **THEN** the v4 component response returns both values for that entry

#### Scenario: Blank value is absent
- **WHEN** an entry is saved with `checkoutDirectory: ""` and `sourcePath: " "`
- **THEN** the write is not rejected for those fields and the entry is stored and returned without
  either field

#### Scenario: v2 exposure
- **WHEN** the v2 VCS settings are requested for a version covered by that configuration
- **THEN** the entry carries `sourcePath` and `checkoutDirectory` with the same values

#### Scenario: Unplaced entries unchanged in v2
- **WHEN** a component has no placement data
- **THEN** its v2 VCS settings JSON is identical to the response before this change

### Requirement: Placement validation

The registry SHALL reject a v4 write that replaces VCS entries of any configuration row, base or
per-range, with a 400 whose `errorMessage` starts with `vcsEntries[<i>].<field>: `, when the row:
has more than one entry and an entry lacks `checkoutDirectory`; has a `checkoutDirectory` that does
not match `^[A-Za-z0-9_][A-Za-z0-9._-]*$`, repeats another entry's `checkoutDirectory` or is
`report-templates` or `sonar-config`; has a `sourcePath` with a segment that is empty, `.`, `..`
or does not match `^[A-Za-z0-9._-]+$` (which excludes absolute paths); or has two entries with the
same repository and `sourcePath`, reported on the later entry's `sourcePath`.

#### Scenario: Multi-entry row without placement
- **WHEN** a write leaves a row with two entries and the second has no `checkoutDirectory`
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[1].checkoutDirectory: `

#### Scenario: Per-range row validated too
- **WHEN** a field-override write sets a VCS marker row to two entries, one without
  `checkoutDirectory`
- **THEN** the write fails with 400 naming that entry's `checkoutDirectory`

#### Scenario: Leading dot
- **WHEN** a write sets `checkoutDirectory` to `.hidden`
- **THEN** the write fails with 400 naming that field

#### Scenario: Nested checkout directory
- **WHEN** a write sets `checkoutDirectory` to `a/b`
- **THEN** the write fails with 400 naming that field

#### Scenario: Escaping source path
- **WHEN** a write sets `sourcePath` to `../other`, `/abs` or `a b`
- **THEN** the write fails with 400 naming that field

#### Scenario: Reserved name
- **WHEN** a write sets `checkoutDirectory` to `report-templates`
- **THEN** the write fails with 400 naming that field

#### Scenario: Same repository and path twice
- **WHEN** entries 0 and 2 of one row have the same repository (ignoring case for Git) and the same
  `sourcePath`
- **THEN** the write fails with 400 and `errorMessage` starting `vcsEntries[2].sourcePath: `

### Requirement: Derived name

The registry SHALL ignore `name` in v4 requests and SHALL store as `name` the entry's
`checkoutDirectory` when set; otherwise the stored name of the row's only entry when the row had
exactly one entry before the write; otherwise `main`.

#### Scenario: Name equals checkout directory
- **WHEN** an entry is saved with `checkoutDirectory: "core"` and `name: "other"`
- **THEN** the stored and returned name is `core`

#### Scenario: Single entry placed
- **WHEN** a single-entry row named `main` is saved with `checkoutDirectory: "core"`
- **THEN** the name is `core`, in v4 and in the v2 VCS settings

#### Scenario: Single entry keeps its name
- **WHEN** a single-entry row named `main` is saved without `checkoutDirectory`
- **THEN** the name stays `main`

#### Scenario: Single entry re-pointed to another repository
- **WHEN** a single-entry row named `main` is saved with a different repository and no
  `checkoutDirectory`
- **THEN** the name stays `main`

#### Scenario: Two entries reduced to one
- **WHEN** a row with entries `alpha` and `beta` is saved with one entry and no `checkoutDirectory`
- **THEN** the name is `main`

#### Scenario: New unplaced entry
- **WHEN** a component is created with one entry without `checkoutDirectory`
- **THEN** its name is `main`

### Requirement: Migration of existing multi-entry rows

The schema migration SHALL set `checkoutDirectory` to `name` for every entry of every configuration
row that has more than one entry, SHALL leave other entries without placement, and SHALL not fail
on names that are not valid Checkout Directories. The DSL import SHALL apply the same rule to the
rows it creates.

#### Scenario: Migrated rows
- **WHEN** the migration runs on a database with a two-entry row named `alpha`/`beta` and a
  single-entry row
- **THEN** the two entries have `checkoutDirectory` `alpha` and `beta`, and the single entry has
  none

#### Scenario: Migrated row with unusable names
- **WHEN** the migration runs on a two-entry row whose names are both `main`, and on one whose
  name is `a/b`
- **THEN** the migration succeeds and copies the names; a later unchanged v4 save of such a row
  fails with 400 naming the offending `checkoutDirectory`

#### Scenario: Imported multi-root component
- **WHEN** the DSL import creates a row with roots `alpha` and `beta`
- **THEN** both entries have `checkoutDirectory` equal to their names, and an unchanged v4 save of
  the row succeeds

### Requirement: Chain-mismatch warning

The registry SHALL include in the v4 component detail response to a successful write a warning that
the TeamCity build chain must be recreated, when the request carries base-configuration
`vcsEntries` and the component has a linked TeamCity project; `warnings` SHALL be an empty list
otherwise, including on GET. Writes of VCS marker rows (per-range overrides) SHALL not warn.

#### Scenario: Entry added with linked project
- **WHEN** an entry is added to a component with a linked TeamCity project
- **THEN** the response `warnings` contains the chain-mismatch warning

#### Scenario: No linked project
- **WHEN** VCS entries are written for a component without a linked TeamCity project
- **THEN** the response `warnings` is empty

#### Scenario: Unrelated edit
- **WHEN** only the component's display name changes
- **THEN** the response `warnings` is empty

#### Scenario: Marker-row error in a combined PATCH
- **WHEN** a component PATCH carries `fieldOverrides` whose second entry is a VCS marker row with
  two entries and no Checkout Directory on its first entry
- **THEN** the response is 400 with `errorMessage` starting
  `fieldOverrides[1].vcsEntries[0].checkoutDirectory: `

#### Scenario: Marker-row write
- **WHEN** a VCS marker row of a component with a linked TeamCity project is written, through the
  field-override endpoints or the `fieldOverrides` of a component PATCH that carries no base
  `vcsEntries`
- **THEN** no chain-mismatch warning is returned

### Requirement: Compatible v2 DTO

`VersionControlSystemRootDTO` SHALL keep a six-parameter JVM constructor and the order of its
first six properties, with the new properties appended.

#### Scenario: Positional construction and destructuring
- **WHEN** Kotlin code constructs the DTO with the previous six arguments and destructures its first
  two components
- **THEN** it compiles and yields `name` and `vcsPath`

#### Scenario: Six-argument constructor from Groovy
- **WHEN** Groovy code calls `new VersionControlSystemRootDTO(...)` with the previous six arguments
- **THEN** it compiles and runs, with both new properties null
