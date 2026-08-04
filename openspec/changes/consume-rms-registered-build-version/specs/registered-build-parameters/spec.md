## Purpose

Defines how CRS's v4 API exposes RMS's registered ("ACTUAL") `build.javaVersion`/`build.mavenVersion` alongside a component's manually configured (DEFAULT/OVERRIDDEN) values, and how ACTUAL gates writes to those configured values. This spec applies only to `build.javaVersion` and `build.mavenVersion` — no other DEFAULT/OVERRIDDEN attribute is affected.

## ADDED Requirements

### Requirement: ACTUAL is exposed as independent, line-aware, per-attribute ranges

CRS's v4 component detail response SHALL include RMS's registered Java version and Maven version as two independent lists of version ranges. A range boundary in one attribute's list SHALL NOT depend on the other attribute's value changing. Ranges SHALL be computed per major-version line — a range SHALL NOT span across a major-version-line boundary even if the value on both sides is the same.

#### Scenario: Independent attribute boundaries

- **WHEN** a component's Java version changes at version `3.0` but its Maven version does not
- **THEN** the Java range list has a boundary at `3.0` and the Maven range list does not

#### Scenario: A line boundary splits a range even when values match

- **WHEN** major-line 2's builds and major-line 3's builds both record the same Java version
- **THEN** ACTUAL still shows two separate ranges, one per line, not one range spanning both

### Requirement: Only RC/RELEASE builds are considered

ACTUAL SHALL be derived only from RMS builds with status `RC` or `RELEASE`.

#### Scenario: A BUILD-status build is ignored

- **WHEN** a component has a build with status `BUILD` recording a Java version different from the surrounding RC/RELEASE builds
- **THEN** that build does not appear in, or alter, the ACTUAL ranges

### Requirement: A leading gap before the first build in a line stays uncovered

Within a major-version line, an ACTUAL range starts at the first build carrying a value. Any stretch before that first build in that line SHALL NOT be part of any ACTUAL range.

#### Scenario: Never-built prefix within a line is not covered

- **WHEN** a version line's earliest RC/RELEASE build carrying a Java version is at `2.5`, and no earlier version in that line was ever built
- **THEN** ACTUAL has no range covering versions before `2.5` in that line, and that stretch remains overridable

### Requirement: Only the highest known line's last run is open-ended

For a given attribute, every line's ACTUAL range is bounded by the start of the next line, except the highest known line, whose last run has no upper bound.

#### Scenario: A future, unbuilt version line is covered by the last known value

- **WHEN** the last RC/RELEASE build RMS has recorded for a component's Java version is in the highest known line, at version `3.4`, and no build exists at or beyond `4.0`
- **THEN** ACTUAL's Java range for that value extends from `3.4` with no upper bound, and covers `4.0` and beyond

#### Scenario: A lower line's run ends at the next line's start, not at its own last build

- **WHEN** a lower major-version line's last build is well before that line's upper boundary, and the next line's first build begins immediately after the boundary
- **THEN** ACTUAL's range for the lower line ends exactly at the next line's start, not at the lower line's last observed build

### Requirement: A DEFAULT/OVERRIDDEN row that disagrees with ACTUAL is flagged as a named warning, never blocked retroactively

A stored DEFAULT or OVERRIDDEN value that disagrees with an intersecting ACTUAL range SHALL be shown with a warning naming the disagreeing sub-range and ACTUAL's value there. A row intersecting several differently-valued ACTUAL ranges SHALL show one warning entry per disagreement. This applies regardless of how the mismatch arose and SHALL NOT cause the stored value to be altered, cleared, or rejected after the fact.

#### Scenario: A pre-existing override warns instead of blocking

- **WHEN** an OVERRIDDEN range's configured Java version was set before RMS ever recorded a build for that range, and RMS's ACTUAL for an intersecting sub-range later reports a different value
- **THEN** the override row is shown with a warning naming that sub-range and ACTUAL's value, and its stored value is unchanged

#### Scenario: Deleting an override can reveal a warning, not an error

- **WHEN** a field override is deleted and the range falls back to a value (DEFAULT, or a shadowing override) that disagrees with ACTUAL for that range
- **THEN** the deletion succeeds, and the resulting row is shown with a warning on the next read

#### Scenario: A row intersecting multiple disagreeing ACTUAL ranges shows multiple warnings

- **WHEN** a single OVERRIDDEN row's range spans two ACTUAL ranges with two different, disagreeing values
- **THEN** the row shows two separate warning entries, one per disagreeing sub-range

### Requirement: The components list view shows one rollup value per attribute

The list/summary response SHALL show a single value per attribute: the maximum version number seen across all of that attribute's ACTUAL ranges, using the same value normalization as range collapsing (e.g. Java `"1.8"` and `"8"` are the same version and must not be treated as two different maxima).

#### Scenario: Maximum, not most recent

- **WHEN** a component's ACTUAL Java ranges are `[1.0,2.0)` → 17 and `[2.0,)` → 11
- **THEN** the summary rollup shows 17

#### Scenario: Equivalent spellings do not produce a false maximum

- **WHEN** a component's ACTUAL Java ranges include one recorded as `"8"` and another recorded as `"1.8"`, and no other Java version is recorded
- **THEN** the summary rollup reflects a single Java 8 value, not two distinct values

### Requirement: Display degrades gracefully when RMS is unreachable

If RMS cannot be reached when the cached ACTUAL report was last refreshed, the affected component(s) SHALL report ACTUAL as unavailable, distinguishable from ACTUAL being null, and the rest of the component response SHALL still be returned. Warnings SHALL continue to be computed against the last successfully cached ACTUAL data, and SHALL be shown as unavailable only for a component that has never had a successful sweep.

#### Scenario: RMS unreachable at read time, prior data exists

- **WHEN** RMS could not be reached during the most recent scheduled refresh, but a prior successful sweep populated the cache
- **THEN** the component response returns normally, ACTUAL and its warnings are served from that prior cached data, and the report indicates the refresh failed

#### Scenario: RMS unreachable at read time, no prior data exists

- **WHEN** RMS could not be reached and no sweep has ever succeeded for a component
- **THEN** ACTUAL and any warnings for that component are reported as unavailable

#### Scenario: Cache staleness is visible

- **WHEN** a caller inspects the ACTUAL report
- **THEN** it can tell when the data was last successfully generated, separately from when the last refresh attempt occurred, and whether the last attempt failed

### Requirement: A write is blocked only when it would introduce a disagreement with ACTUAL

Creating or updating a DEFAULT or OVERRIDDEN value for `build.javaVersion`/`build.mavenVersion` SHALL be rejected only when all of the following hold: the write changes the effective stored value and/or range; the resulting range intersects a non-null ACTUAL value for that attribute; and the resulting value disagrees with that intersecting ACTUAL value. This check is evaluated per range and per attribute.

#### Scenario: Unchanged resend is never blocked

- **WHEN** a write resends the current, unchanged `javaVersion` value and range for a component whose ACTUAL data disagrees with that value
- **THEN** the write is permitted — no effective change means the check does not apply

#### Scenario: Writing a value that matches ACTUAL is always permitted

- **WHEN** an editor writes a `build.javaVersion` value, for a range, that equals ACTUAL's value for that range
- **THEN** the write is permitted, even though the range intersects non-null ACTUAL data — this is how an existing warning is resolved

#### Scenario: Writing a disagreeing value into ACTUAL-covered territory is blocked

- **WHEN** an editor attempts to write a `build.javaVersion` value, for a range, that differs from ACTUAL's non-null value for an intersecting sub-range
- **THEN** the write is rejected

#### Scenario: Moving an override's range onto ACTUAL-covered territory is blocked the same way

- **WHEN** an editor updates a field override's `versionRange` (leaving its value unchanged) so that the new range intersects a non-null ACTUAL value the old range did not, and the override's value disagrees with that ACTUAL value
- **THEN** the write is rejected

#### Scenario: DEFAULT write blocked by a disagreeing ACTUAL value anywhere, even a fully-shadowed range

- **WHEN** an editor attempts to write the DEFAULT (`ALL_VERSIONS`) Java version to a value that disagrees with ACTUAL's Java value for some range — even a range that is already fully covered by an existing OVERRIDDEN row, such that the DEFAULT write would not change what is actually served there
- **THEN** the write is still rejected

#### Scenario: Independent attributes

- **WHEN** ACTUAL's Java value is non-null and disagreeing for a range but its Maven value is null for that same range
- **THEN** writing the Maven override for that range is still permitted; only the Java write is blocked

#### Scenario: Write permitted where ACTUAL is null for that range

- **WHEN** ACTUAL for the specific range and attribute being written is null
- **THEN** the write is permitted, unaffected by ACTUAL values on other, non-intersecting ranges

#### Scenario: createComponent is covered

- **WHEN** a new component is created with a `baseConfiguration.build.javaVersion` value that disagrees with ACTUAL already registered under that component's key
- **THEN** the create request is rejected on the same basis as an update would be

### Requirement: Deleting a field override requires no ACTUAL check

`deleteFieldOverride` SHALL NOT be gated by ACTUAL, regardless of value or disagreement.

#### Scenario: Deletion is always permitted

- **WHEN** an editor deletes a `build.javaVersion` or `build.mavenVersion` field override, regardless of what ACTUAL reports for that range
- **THEN** the deletion succeeds

### Requirement: Writes are checked live, never against the cached display

Every write attempt to `build.javaVersion`/`build.mavenVersion` SHALL check ACTUAL with a live call at write time, querying only the specific attribute being written, independent of the cached report used for display.

#### Scenario: A just-registered value blocks a write before the next display refresh

- **WHEN** RMS registers a new, disagreeing non-null value for a range moments after the last display-cache refresh, before a write is attempted
- **THEN** the write is still rejected, even though the cached display has not yet caught up

### Requirement: An ambiguous or failed live check fails closed

Only a confirmed response with no matching builds for the range/attribute in question counts as "ACTUAL is null → write permitted." Any other outcome from the live call — an error response, a timeout, or a connection failure — SHALL reject the write.

#### Scenario: RMS unreachable at write time

- **WHEN** the live RMS call made to evaluate a write fails or times out
- **THEN** the write is rejected, distinguishable in the response from a rejection caused by an explicit disagreeing ACTUAL value

#### Scenario: An ambiguous response is not read as "no data"

- **WHEN** the live RMS call returns a response that does not clearly confirm zero matching builds (e.g. an error status)
- **THEN** the write is rejected rather than treated as ACTUAL being null

### Requirement: A disabled or unconfigured RMS integration does not silently disable enforcement

When RMS integration is disabled or its URL is unconfigured, the write gate SHALL reject writes the same way it does for an unreachable RMS, rather than skipping the check. This condition SHALL be distinguishable from a genuine reachability failure in operator-facing diagnostics.

#### Scenario: Integration disabled

- **WHEN** RMS integration is disabled (or its base URL is blank) and an editor attempts to write `build.javaVersion`/`build.mavenVersion`
- **THEN** the write is rejected, the same as if the live call had failed, and the failure is logged/reported as "integration not configured" rather than "RMS unreachable"
