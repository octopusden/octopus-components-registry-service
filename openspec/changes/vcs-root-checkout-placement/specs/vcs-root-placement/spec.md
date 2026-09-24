## Purpose

Stores, validates and exposes where each VCS entry's sources are placed, so build automation can
check out multi-root and shared-repository components from registry data alone.

## ADDED Requirements

### Requirement: Placement fields on VCS entries

The registry SHALL accept `sourcePath` and `checkoutDirectory` on each v4 VCS entry, store them per
configuration row, and return them in v4 responses and in the legacy v2 VCS settings, omitting a
field that is empty.

#### Scenario: v4 round-trip
- **WHEN** a component is updated with an entry carrying `sourcePath: "mapper"` and
  `checkoutDirectory: "core"`
- **THEN** the v4 component response returns both values for that entry

#### Scenario: v2 exposure
- **WHEN** the v2 VCS settings are requested for a version covered by that configuration
- **THEN** the entry carries `sourcePath` and `checkoutDirectory` with the same values

#### Scenario: Unplaced entries unchanged in v2
- **WHEN** a component has no placement data
- **THEN** its v2 VCS settings JSON is identical to the response before this change

### Requirement: Placement validation

The registry SHALL reject a v4 write, with a field-prefixed validation error, when for any
configuration row: it has more than one entry and an entry lacks `checkoutDirectory`; a
`checkoutDirectory` is not a single directory name, is `.` or `..`, repeats another entry's
`checkoutDirectory` or is reserved; a `sourcePath` is absolute or has an empty, `.` or `..`
segment; or two entries share repository and `sourcePath`.

#### Scenario: Multi-entry row without placement
- **WHEN** a write leaves a row with two entries and one has no `checkoutDirectory`
- **THEN** the write fails with 400 naming `vcsEntries[<i>].checkoutDirectory`

#### Scenario: Nested checkout directory
- **WHEN** a write sets `checkoutDirectory` to `a/b`
- **THEN** the write fails with 400 naming that field

#### Scenario: Escaping source path
- **WHEN** a write sets `sourcePath` to `../other`
- **THEN** the write fails with 400 naming that field

#### Scenario: Reserved name
- **WHEN** a write sets `checkoutDirectory` to `report-templates`
- **THEN** the write fails with 400 naming that field

#### Scenario: Same repository and path twice
- **WHEN** two entries of one row have the same repository (ignoring case for Git) and the same
  `sourcePath`
- **THEN** the write fails with 400

### Requirement: Derived name

The registry SHALL ignore `name` in v4 requests and SHALL store as `name` the entry's
`checkoutDirectory` when set; otherwise the existing name of a row's single entry, or the
repository slug for a new entry.

#### Scenario: Name equals checkout directory
- **WHEN** an entry is saved with `checkoutDirectory: "core"` and `name: "other"`
- **THEN** the stored and returned name is `core`

#### Scenario: Single entry keeps its name
- **WHEN** a single-entry row named `main` is saved without `checkoutDirectory`
- **THEN** the name stays `main`

#### Scenario: New unplaced entry
- **WHEN** a new single entry with repository `ssh://git@example.test/proj/repo-a.git` is saved
  without `checkoutDirectory`
- **THEN** its name is `repo-a`

### Requirement: Migration of existing multi-entry rows

The schema migration SHALL set `checkoutDirectory` to `name` for every entry of every configuration
row that has more than one entry, and SHALL leave other entries without placement.

#### Scenario: Migrated rows
- **WHEN** the migration runs on a database with a two-entry row named `alpha`/`beta` and a
  single-entry row
- **THEN** the two entries have `checkoutDirectory` `alpha` and `beta`, and the single entry has
  none

### Requirement: Chain-mismatch warning

The registry SHALL include in the v4 component detail response to a successful write a warning that
the TeamCity build chain must be recreated, when the write adds, removes or re-places a VCS entry
and the component has a linked TeamCity project; `warnings` SHALL be empty otherwise.

#### Scenario: Entry added with linked project
- **WHEN** an entry is added to a component with a linked TeamCity project
- **THEN** the response `warnings` contains the chain-mismatch warning

#### Scenario: Unrelated edit
- **WHEN** only the component's display name changes
- **THEN** the response `warnings` is empty

### Requirement: Compatible v2 DTO

`VersionControlSystemRootDTO` SHALL keep its six-parameter constructor and the order of its first
six properties, with the new properties appended.

#### Scenario: Positional construction and destructuring
- **WHEN** code constructs the DTO with the previous six arguments and destructures its first two
  components
- **THEN** it compiles and yields `name` and `vcsPath`
