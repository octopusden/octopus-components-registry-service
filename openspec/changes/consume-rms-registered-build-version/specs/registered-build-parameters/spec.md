## Purpose

Defines how CRS's v4 API exposes RMS's registered ("ACTUAL") `build.javaVersion`/`build.mavenVersion` alongside a component's manually configured (DEFAULT/OVERRIDDEN) values, and how ACTUAL gates writes to those configured values. This spec applies only to `build.javaVersion` and `build.mavenVersion` — no other DEFAULT/OVERRIDDEN attribute is affected.

## ADDED Requirements

### Requirement: ACTUAL is exposed as independent, minor-version-aware, per-attribute ranges

CRS's v4 component detail response SHALL include RMS's registered Java version and Maven version as two independent lists of version ranges. A range boundary in one attribute's list SHALL NOT depend on the other attribute's value changing. Ranges SHALL be computed per minor version (`major.minor`) — a range SHALL NOT span across a minor-version boundary even if the value on both sides is the same.

#### Scenario: Independent attribute boundaries

- **WHEN** a component's Java version changes at version `3.0` but its Maven version does not
- **THEN** the Java range list has a boundary at `3.0` and the Maven range list does not

#### Scenario: A minor-version boundary splits a range even when values match

- **WHEN** minor `2.5`'s builds and minor `2.6`'s builds record different values, and minor `2.6`'s builds and minor `2.7`'s builds happen to record the same value as each other
- **THEN** ACTUAL still shows `2.6` and `2.7` as one merged range only because they're adjacent AND equal-valued — `2.5` remains its own separate range regardless of value

### Requirement: Only RC/RELEASE builds are considered

ACTUAL SHALL be derived only from RMS builds with status `RC` or `RELEASE`. Hotfix builds are included with no special handling — a hotfix is expected to always carry its parent minor's value.

#### Scenario: A BUILD-status build is ignored

- **WHEN** a component has a build with status `BUILD` recording a Java version different from the surrounding RC/RELEASE builds
- **THEN** that build does not appear in, or alter, the ACTUAL ranges

### Requirement: A minor version with no builds is always free, regardless of where it falls

A minor version with zero recorded RC/RELEASE builds SHALL NOT be part of any ACTUAL range — whether it comes before the first ever build, after the most recent one, or between two other minors that do have data. Nothing is inferred or filled in across an unbuilt minor.

#### Scenario: Never-built prefix stays free

- **WHEN** a component's earliest RC/RELEASE build carrying a Java version is at minor `2.5`, and no earlier minor was ever built
- **THEN** ACTUAL has no range covering minors before `2.5`, and that stretch remains overridable

#### Scenario: An unbuilt minor between two data-bearing minors stays free

- **WHEN** minor `2.6` has RC/RELEASE builds, minor `2.7` has none, and minor `3.4` has RC/RELEASE builds
- **THEN** minor `2.7` is not part of any ACTUAL range, regardless of whether `2.6`'s and `3.4`'s values agree or differ

### Requirement: Only the single highest data-bearing minor's range is open-ended

For a given attribute, every minor's ACTUAL range is bounded (per the requirement above), except the single highest minor RMS has any RC/RELEASE data for, whose range has no upper bound.

#### Scenario: A future, unbuilt version is covered by the last known value

- **WHEN** the highest minor RMS has recorded a Java version for is `3.4`, and no build exists at or beyond `4.0`
- **THEN** ACTUAL's Java range for that value extends from `3.4` with no upper bound, and covers `4.0` and beyond

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

### Requirement: Comparing values normalizes known spelling differences, and fails closed on unparseable ones

Two values are considered equal, for both range collapsing and the write check, only after normalization: Java's legacy `1.X` spelling and the short `X` spelling (e.g. `1.8` and `8`) SHALL be treated as the same version; Maven values SHALL be compared with a version-aware comparison, not raw string equality. A build whose version string cannot be parsed by CRS's version-comparison logic SHALL NOT be silently placed into a minor group by a fallback comparator — it SHALL be excluded and treated as fail-closed wherever this affects a write decision (see the write-check requirements below).

#### Scenario: Equivalent Java spellings do not produce a false maximum

- **WHEN** a component's ACTUAL Java ranges include one recorded as `"8"` and another recorded as `"1.8"`, and no other Java version is recorded
- **THEN** the summary rollup (below) reflects a single Java 8 value, not two distinct values

#### Scenario: An unparseable build version does not silently affect a write decision

- **WHEN** the write-time check encounters a build whose version string cannot be parsed
- **THEN** the write is rejected rather than proceeding on a result that may have mis-binned that build into the wrong minor

### Requirement: The components list view shows one rollup value per attribute

The list/summary response SHALL show a single value per attribute: the maximum version number seen across all of that attribute's ACTUAL ranges, using the normalization above. This reflects the highest version ever recorded across any range — not the value of the component's current (highest-minor) range, which may differ.

#### Scenario: Maximum, not most recent

- **WHEN** a component's ACTUAL Java ranges are `[1.0,2.0)` → 17 and `[2.0,)` → 11
- **THEN** the summary rollup shows 17

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

Creating or updating a DEFAULT or OVERRIDDEN value for `build.javaVersion`/`build.mavenVersion` SHALL be rejected only when all of the following hold: the write changes the effective stored value and/or range; the resulting range intersects a non-null ACTUAL value for that attribute; and the resulting value disagrees with **any one** of the intersecting ACTUAL values (a range can intersect more than one ACTUAL range, most commonly for DEFAULT, which spans every version at once). This check is evaluated per range and per attribute.

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

#### Scenario: DEFAULT can become permanently unwritable, and this is expected

- **WHEN** a component's ACTUAL Java ranges include two or more different, disagreeing values across its history
- **THEN** no single value written to DEFAULT can agree with all of them, so every write to DEFAULT is rejected — this is accepted behavior, not a defect, and there is no remedy (DEFAULT cannot be deleted)

#### Scenario: Independent attributes

- **WHEN** ACTUAL's Java value is non-null and disagreeing for a range but its Maven value is null for that same range
- **THEN** writing the Maven override for that range is still permitted; only the Java write is blocked

#### Scenario: Write permitted where ACTUAL is null for that range

- **WHEN** ACTUAL for the specific range and attribute being written is null
- **THEN** the write is permitted, unaffected by ACTUAL values on other, non-intersecting ranges

#### Scenario: createComponent is covered

- **WHEN** a new component is created with a `baseConfiguration.build.javaVersion` value that disagrees with ACTUAL already registered under that component's key
- **THEN** the create request is rejected on the same basis as an update would be

### Requirement: Deleting a field override requires no ACTUAL check, but recreating it with the same disagreeing value is blocked like any other write

`deleteFieldOverride` SHALL NOT be gated by ACTUAL, regardless of value or disagreement. A subsequent create using the same range and a value that disagrees with ACTUAL SHALL be evaluated as a new write, per the write-blocking requirement above.

#### Scenario: Deletion is always permitted

- **WHEN** an editor deletes a `build.javaVersion` or `build.mavenVersion` field override, regardless of what ACTUAL reports for that range
- **THEN** the deletion succeeds

#### Scenario: Recreating the same disagreeing override is blocked

- **WHEN** an editor deletes a field override that disagreed with ACTUAL, then attempts to recreate it with the same range and the same, still-disagreeing value
- **THEN** the recreate is rejected, the same as any other write that would introduce a disagreement — deletion does not grant a right to restore a disagreeing value

### Requirement: Writes are checked live, never against the cached display

Every write attempt to `build.javaVersion`/`build.mavenVersion` SHALL check ACTUAL with a live call at write time, querying only the specific attribute being written, independent of the cached report used for display.

#### Scenario: A just-registered value blocks a write before the next display refresh

- **WHEN** RMS registers a new, disagreeing non-null value for a range moments after the last display-cache refresh, before a write is attempted
- **THEN** the write is still rejected, even though the cached display has not yet caught up

### Requirement: An ambiguous or failed live check fails closed

Only a confirmed response with no matching builds for the range/attribute in question counts as "ACTUAL is null → write permitted." Any other outcome from the live call — an error response, a timeout, a connection failure, or an unparseable build version among the results — SHALL reject the write.

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
