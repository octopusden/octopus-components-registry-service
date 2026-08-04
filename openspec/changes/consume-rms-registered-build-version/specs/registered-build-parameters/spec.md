## Purpose

Defines how CRS's v4 API exposes the Java/Maven version RMS actually recorded as used for a component's builds, and how that data gates edits to CRS's own configured Java/Maven version.

## ADDED Requirements

### Requirement: Registered value is exposed as a list of ranges

CRS's v4 component detail response SHALL include RMS's registered Java and Maven version for the component, expressed as a list of version ranges — never a single scalar value.

#### Scenario: Multiple version lines with different registered values

- **WHEN** a component has RC/RELEASE builds recording Java 11 for its `1.x` line and Java 17 for its `2.x` line
- **THEN** the response lists both ranges, each with its own registered Java version

#### Scenario: Consecutive same-value builds collapse into one range

- **WHEN** several consecutive RC/RELEASE builds all recorded the same Java and Maven version
- **THEN** they are represented as a single range in the response, not one entry per build

### Requirement: Only RC/RELEASE builds are considered

The registered value SHALL be derived only from RMS builds with status `RC` or `RELEASE`. Builds in any other status SHALL NOT influence the registered value or the ranges shown.

#### Scenario: A BUILD-status build is ignored

- **WHEN** a component has a build with status `BUILD` recording a Java version different from the surrounding RC/RELEASE builds
- **THEN** that build does not appear in, or alter, the registered ranges

### Requirement: A range with no recorded value is distinguished from an unreachable RMS

The response SHALL distinguish "RMS has no recorded value for this range" (a null registered value) from "RMS could not be reached" (the whole field unavailable). A caller must never read one as the other.

#### Scenario: RMS explicitly has no data for a range

- **WHEN** RMS's RC/RELEASE builds for a range never recorded a Java version
- **THEN** that range's registered Java version is shown as null, not as unavailable

#### Scenario: RMS is unreachable

- **WHEN** RMS cannot be reached at the time the cached report was last refreshed
- **THEN** the registered-value field is marked unavailable for the affected component(s), and the rest of the component response is returned normally

### Requirement: Display is served from a periodically refreshed cache

The registered-value display SHALL be served from a cache that is refreshed on a schedule, not recomputed per request. A failed refresh SHALL retain the previously cached data rather than clearing it.

#### Scenario: A failed refresh does not blank the display

- **WHEN** a scheduled refresh fails to reach RMS
- **THEN** the previously cached registered ranges continue to be served, and the report indicates the refresh failed and when it was last attempted

#### Scenario: Cache staleness is visible

- **WHEN** a caller inspects the registered-value report
- **THEN** it can tell when the data was last successfully generated, separately from when the last refresh attempt occurred

### Requirement: Editing a range is blocked where RMS has a registered value

Editing the configured `javaVersion` or `mavenVersion` for a version range SHALL be rejected if RMS's registered value for any part of that range is non-null, for that same attribute. This check SHALL be evaluated per range and per attribute — a registered Java version does not block editing Maven version, and vice versa.

#### Scenario: Base configuration edit blocked by a registered value

- **WHEN** an editor attempts to set the base (default) `javaVersion` while RMS has registered a non-null Java version for any range of that component
- **THEN** the edit is rejected

#### Scenario: Field-override edit blocked by an intersecting registered range

- **WHEN** an editor attempts to create or update a `build.javaVersion` field override for a specific version range, and RMS's registered Java version for an intersecting range is non-null
- **THEN** the edit is rejected

#### Scenario: Independent attributes

- **WHEN** RMS has registered a non-null Java version for a range but no Maven version for that same range
- **THEN** editing the Maven version override for that range is still permitted, only the Java version edit is blocked

### Requirement: Editing is permitted where RMS has no registered value

- **WHEN** RMS's registered value for the specific range and attribute being edited is null
- **THEN** the edit is permitted, unaffected by registered values on other, non-intersecting ranges

### Requirement: Edits are checked live, not against the cached display

Every edit attempt to `javaVersion`/`mavenVersion` SHALL check RMS's registered value with a live call at write time, independent of the cached report used for display.

#### Scenario: A just-registered value blocks an edit before the next display refresh

- **WHEN** RMS registers a new non-null value for a range moments after the last display-cache refresh, before the edit is attempted
- **THEN** the edit is still rejected, even though the cached display has not yet caught up

### Requirement: RMS unavailability at write time blocks the edit

- **WHEN** the live RMS call made to evaluate an edit fails or times out
- **THEN** the edit is rejected, with a response distinguishable from a rejection caused by an explicit non-null registered value
