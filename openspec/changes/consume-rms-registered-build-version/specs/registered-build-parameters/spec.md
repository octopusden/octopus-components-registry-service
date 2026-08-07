## Purpose

Defines how CRS's v4 API exposes RMS's registered ("ACTUAL") `build.javaVersion`/`build.mavenVersion` alongside a component's manually configured (DEFAULT/OVERRIDDEN) values, and how ACTUAL gates writes to those configured values. This spec applies only to `build.javaVersion` and `build.mavenVersion` — no other DEFAULT/OVERRIDDEN attribute is affected.

## ADDED Requirements

### Requirement: ACTUAL applies only to non-archived Maven and Gradle components

ACTUAL (display and write gate alike) SHALL apply only to components whose build system is `MAVEN` or `GRADLE` and which are not archived. For any other build system, or for an archived component, no RMS call is made for that component, no ACTUAL data is shown, and no write gate applies to its `javaVersion`/`mavenVersion` fields.

#### Scenario: A non-Maven/Gradle component is unaffected

- **WHEN** a component's build system is neither `MAVEN` nor `GRADLE`
- **THEN** its responses carry no ACTUAL data, and writes to its `javaVersion`/`mavenVersion` fields (if any) are never gated by this feature

#### Scenario: ECLIPSE_MAVEN is excluded, not treated as Maven-like

- **WHEN** a component's build system is `ECLIPSE_MAVEN`
- **THEN** it is treated the same as any other non-`MAVEN`/`GRADLE` build system — no ACTUAL data, no write gate

#### Scenario: An archived component is unaffected, distinctly from "never successfully swept"

- **WHEN** a component is archived, regardless of its build system
- **THEN** its detail response carries no ACTUAL data at all — `null`, not an "ACTUAL data unavailable" indicator, since an archived component is never swept and there was never an attempt to check RMS for it

### Requirement: ACTUAL is exposed as independent, per-attribute ranges built from the real build sequence

CRS's v4 component detail response SHALL include RMS's registered Java version and Maven version as two independent lists of version ranges. A range boundary in one attribute's list SHALL NOT depend on the other attribute's value changing. Ranges SHALL be built by walking the component's RC/RELEASE builds in real version order (not bucketed by any version-format-dependent grouping) and collapsing consecutive builds that share the same (normalized) value into one range; a build with a different or null value ends the run before it.

#### Scenario: Independent attribute boundaries

- **WHEN** a component's Java version changes at a build where its Maven version does not
- **THEN** the Java range list has a boundary there and the Maven range list does not

#### Scenario: A run bridges an unbuilt stretch between two agreeing builds

- **WHEN** two builds recording the same Java version are not adjacent version numbers (nothing was built at all in between)
- **THEN** both builds are part of one continuous ACTUAL range spanning the gap — the range reflects the sequence of observed builds, not a claim that every intermediate version was itself tested

### Requirement: Only RC/RELEASE builds are considered

ACTUAL SHALL be derived only from RMS builds with status `RC` or `RELEASE`. Hotfix builds are included with no special handling — a hotfix is expected to always carry its parent version line's value.

#### Scenario: A BUILD-status build is ignored

- **WHEN** a component has a build with status `BUILD` recording a Java version different from the surrounding RC/RELEASE builds
- **THEN** that build does not appear in, or alter, the ACTUAL ranges

### Requirement: A build recording no value for an attribute ends the run before it, regardless of position

A build with a null value for an attribute SHALL end any active run of that attribute at that point. A stretch with no RC/RELEASE data at all — before the first build, after the last one, or between two data-bearing builds — SHALL NOT be part of any ACTUAL range.

#### Scenario: Never-built prefix stays free

- **WHEN** a component's earliest RC/RELEASE build carrying a Java version is well after the component's first-ever build, and no build before it ever recorded a Java version
- **THEN** ACTUAL has no range covering that earlier stretch, and it remains overridable

#### Scenario: A null build between two agreeing runs breaks them apart

- **WHEN** a run of builds recording Java version X is followed by a build recording null, which is in turn followed by a later run recording Java version X again
- **THEN** these are two separate ACTUAL ranges, not one continuous range — a null observation always breaks a run, even if the value resumes unchanged afterward

### Requirement: Only a run containing the single highest-version build is open-ended

A run's ACTUAL range has no upper bound only if it contains the highest-version RC/RELEASE build in the component's entire history, and that build's value is non-null. Every other run is bounded at the point it was broken.

#### Scenario: The most recent build extends the range into unbuilt future versions

- **WHEN** the highest-version RC/RELEASE build RMS has recorded for a component records a non-null Java version, and no build exists beyond it
- **THEN** ACTUAL's Java range for that value has no upper bound, and covers versions that don't exist yet

#### Scenario: A higher-version null build closes off what would otherwise be open-ended

- **WHEN** a run of non-null values is the highest-version thing observed, but a build at a still-higher version number (version order, not chronological order — CRS has no build timestamp to order by) has a null value for that attribute
- **THEN** the run is bounded at its own last member, not left open-ended — the higher-version null build proves the "still true" assumption wrong

#### Scenario: An earlier run is always bounded, even if its value never changed before the gap

- **WHEN** a run of builds recording the same value is followed, after an unbuilt or null gap, by a different run — regardless of whether the second run's value agrees or differs
- **THEN** the earlier run's range ends where it was broken, never extending through the gap into the later run

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

### Requirement: Comparing values normalizes known spelling differences

Two values are considered equal, for both range collapsing and the write check, only after normalization: Java's legacy `1.X` spelling and the short `X` spelling (e.g. `1.8` and `8`) SHALL be treated as the same version; Maven values SHALL be compared with a version-aware comparison, not raw string equality. The Maven token `"LATEST"` SHALL be treated as its own distinct value, never equal to any numbered version, and SHALL always be considered the maximum when compared against any numbered version.

#### Scenario: Equivalent Java spellings do not produce a false maximum

- **WHEN** a component's ACTUAL Java ranges include one recorded as `"8"` and another recorded as `"1.8"`, and no other Java version is recorded
- **THEN** the effective Java version (below) reflects a single Java 8 value, not two distinct values

### Requirement: The components list view's javaVersion field shows the effective Java version

The list/summary response's existing `javaVersion` field SHALL show the component's *effective* Java version, not always the raw configured value: RMS's registered Java version when it has any (the maximum, normalized value across the component's ACTUAL Java ranges), else the component's own configured `javaVersion` (BASE row) — the same value it always showed before this requirement. There is no equivalent Maven field on the list view. This reflects the highest version ever recorded across any ACTUAL range — not the value of the component's current (most recent) range, which may differ.

#### Scenario: Maximum, not most recent

- **WHEN** a component's ACTUAL Java ranges are `[1.0,2.0)` → 17 and `[2.0,)` → 11
- **THEN** the list response's `javaVersion` shows 17

#### Scenario: No RMS data falls back to the configured value

- **WHEN** a component has no ACTUAL Java ranges (never swept, or RMS has no data for it)
- **THEN** the list response's `javaVersion` shows the component's configured `javaVersion` (BASE row), exactly as before this requirement

### Requirement: The javaVersion list filter matches the effective value, not the raw configured column

`GET /api/4/components?javaVersion=...` SHALL match a component against its effective Java version (the same value the list response's `javaVersion` field now shows), not directly against the BASE configuration row's `javaVersion` column. Because RMS's data isn't a database column, this filter's matching and the list's pagination SHALL be computed together outside the database query, over every component matching the request's other filters.

#### Scenario: RMS's registered value overrides the configured one for filtering

- **WHEN** a component's configured `javaVersion` is `"8"` but RMS's registered rollup for it is `"21"`
- **THEN** `?javaVersion=21` matches this component and `?javaVersion=8` does not

#### Scenario: No RMS data falls back to the configured value for filtering

- **WHEN** a component has no ACTUAL Java data
- **THEN** `?javaVersion=...` matches it exactly as it would have matched its configured `javaVersion` column directly

### Requirement: Display degrades gracefully when RMS is unreachable

If RMS cannot be reached when the cached ACTUAL report was last refreshed, the affected component(s) SHALL still return a normal response. Each DEFAULT/OVERRIDDEN row SHALL be marked with an "ACTUAL data unavailable" indicator, distinguishable from both a disagreement warning (which means "checked, and it disagrees") and a clean row (which means "checked, and it agrees or there's nothing to compare"). Warnings SHALL continue to be computed against the last successfully cached ACTUAL data where one exists, and SHALL be shown as unavailable only for a component that has never had a successful sweep.

#### Scenario: RMS unreachable at read time, prior data exists

- **WHEN** RMS could not be reached during the most recent scheduled refresh, but a prior successful sweep populated the cache
- **THEN** the component response returns normally, ACTUAL and its warnings are served from that prior cached data, and the report indicates the refresh failed

#### Scenario: RMS unreachable at read time, no prior data exists

- **WHEN** RMS could not be reached and no sweep has ever succeeded for a component
- **THEN** every DEFAULT/OVERRIDDEN row for that component is marked "ACTUAL data unavailable"

#### Scenario: Cache staleness is visible

- **WHEN** a caller inspects the ACTUAL report
- **THEN** it can tell when the data was last successfully generated, separately from when the last refresh attempt occurred, and whether the last attempt failed

#### Scenario: The cache is populated immediately on startup

- **WHEN** CRS starts up
- **THEN** the first sweep runs immediately, rather than waiting for the first scheduled interval to elapse

#### Scenario: Retry cadence backs off after repeated failures, capped at the normal interval

- **WHEN** the sweep fails repeatedly in a row
- **THEN** each successive retry waits longer than the last, up to but never exceeding the normal (successful-case) refresh interval

#### Scenario: A success resets the retry backoff

- **WHEN** a sweep succeeds after one or more failures
- **THEN** the next sweep is scheduled at the normal interval, and a subsequent failure starts the backoff over from its shortest interval, not from where the previous backoff left off

#### Scenario: A 404 during the sweep is treated as unavailable, not as clean

- **WHEN** RMS returns a 404 for a component during the scheduled sweep
- **THEN** that component's ACTUAL is marked unavailable, the same as any other sweep failure — a 404 SHALL NOT be read as "confirmed, no data"

### Requirement: A rejected write immediately refreshes that component's cached ACTUAL data

When a write is rejected because it disagrees with ACTUAL, the component's cached display data SHALL be refreshed immediately using the data already retrieved for that check, rather than waiting for the next scheduled sweep.

#### Scenario: A rejection updates the display without waiting for the next sweep

- **WHEN** a write is rejected due to a disagreeing ACTUAL value
- **THEN** a subsequent read of that component's detail response reflects the ACTUAL data that caused the rejection, even before the next scheduled sweep runs

### Requirement: A write is blocked only when it would introduce a disagreement with ACTUAL

Creating or updating a DEFAULT or OVERRIDDEN value for `build.javaVersion`/`build.mavenVersion` SHALL be rejected only when all of the following hold: the write changes the effective stored value and/or range; the resulting range intersects a non-null ACTUAL value for that attribute; and the resulting value disagrees with **any one** of the intersecting ACTUAL values (a range can intersect more than one ACTUAL range — always true for DEFAULT, which spans every version at once, and also possible for a composite OVERRIDDEN range, whose own segments are each checked independently). This check is evaluated per range and per attribute.

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

#### Scenario: A composite range is checked segment by segment

- **WHEN** a legacy, composite (multi-segment) OVERRIDDEN range is written with a new value
- **THEN** each of the range's segments is checked against ACTUAL independently; the write is permitted only if no segment disagrees, and rejected if any one segment does

#### Scenario: A malformed range fails closed rather than being partially checked

- **WHEN** a write's range cannot be fully parsed as either a single interval or a clean composite of intervals (e.g. a stray, unparseable segment mixed in with valid ones)
- **THEN** the write is rejected if ACTUAL has any data at all for that component and attribute — the range is never partially checked using only the segments that happened to parse

### Requirement: Deleting a field override requires no ACTUAL check, but recreating it with the same disagreeing value is blocked like any other write

`deleteFieldOverride` SHALL NOT be gated by ACTUAL, regardless of value or disagreement. A subsequent create using the same range and a value that disagrees with ACTUAL SHALL be evaluated as a new write, per the write-blocking requirement above.

#### Scenario: Deletion is always permitted

- **WHEN** an editor deletes a `build.javaVersion` or `build.mavenVersion` field override, regardless of what ACTUAL reports for that range
- **THEN** the deletion succeeds

#### Scenario: Recreating the same disagreeing override is blocked

- **WHEN** an editor deletes a field override that disagreed with ACTUAL, then attempts to recreate it with the same range and the same, still-disagreeing value
- **THEN** the recreate is rejected, the same as any other write that would introduce a disagreement — deletion does not grant a right to restore a disagreeing value

### Requirement: Writes are checked live, never against the cached display

Every write attempt to `build.javaVersion`/`build.mavenVersion` SHALL check ACTUAL with a live call at write time, independent of the cached report used for display. The disagreement check itself only evaluates the specific attribute being written.

#### Scenario: A just-registered value blocks a write before the next display refresh

- **WHEN** RMS registers a new, disagreeing non-null value for a range moments after the last display-cache refresh, before a write is attempted
- **THEN** the write is still rejected, even though the cached display has not yet caught up

### Requirement: An ambiguous or failed live check fails closed

Only a confirmed response with no matching builds for the range/attribute in question counts as "ACTUAL is null → write permitted." Any other outcome from the live call — an error response, a timeout, a connection failure, or a response with no body at all — SHALL reject the write.

#### Scenario: RMS unreachable at write time

- **WHEN** the live RMS call made to evaluate a write fails or times out
- **THEN** the write is rejected, distinguishable in the response from a rejection caused by an explicit disagreeing ACTUAL value

#### Scenario: An ambiguous response is not read as "no data"

- **WHEN** the live RMS call returns a response that does not clearly confirm zero matching builds (e.g. an error status)
- **THEN** the write is rejected rather than treated as ACTUAL being null

#### Scenario: An RMS outage only affects the field actually being changed

- **WHEN** RMS is unreachable and a write updates a field other than `build.javaVersion`/`build.mavenVersion` (or resends `build.javaVersion`/`build.mavenVersion` unchanged alongside other real changes)
- **THEN** the write succeeds — the outage never blocks a write that isn't itself changing `build.javaVersion`/`build.mavenVersion`

### Requirement: A disabled RMS integration turns the whole feature off — distinct from RMS being unreachable

When RMS integration is disabled, neither Part A's display nor Part B's write check SHALL run. No ACTUAL data is shown, and no write to `build.javaVersion`/`build.mavenVersion` is blocked by this feature. This is a distinct condition from RMS being unreachable while the feature is enabled (see the requirements above), which fails closed for writes and fails soft for display — a disabled integration does neither, because the feature is simply not active.

#### Scenario: Integration disabled — writes are unaffected

- **WHEN** RMS integration is disabled and an editor writes `build.javaVersion`/`build.mavenVersion`
- **THEN** the write succeeds, behaving exactly as it would if this feature did not exist

#### Scenario: Integration disabled — no ACTUAL data is shown

- **WHEN** RMS integration is disabled and a component's detail or summary response is requested
- **THEN** no ACTUAL ranges, rollup, or warnings are shown — not even an "unavailable" indicator, since there is no attempt to check RMS at all

### Requirement: Enabling the integration without a URL fails application startup, rather than silently behaving as disabled

Configuration where RMS integration is enabled but its base URL is left blank SHALL fail application startup outright. This is deliberately distinct from the disabled requirement above: turning the feature on is an explicit action, and doing so without a URL is a configuration mistake worth surfacing immediately rather than one the application quietly runs through as if the feature were off.

#### Scenario: Enabled with a blank URL fails fast

- **WHEN** RMS integration is enabled and its base URL is blank or unset
- **THEN** the application fails to start, rather than starting up with the feature silently disabled
