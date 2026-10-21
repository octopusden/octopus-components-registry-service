## Purpose

Find VCS checkout-layout drift between TeamCity and Components Registry, and let a user select
components to write, without ever overwriting a manual registry edit. Covers the two CRS jobs
(Diff, Sync) reached from the Portal's Admin → Maintenance page, under
`/rest/api/4/admin/teamcity-checkout-layout` (endpoints: ADR-023 decision 2). Component behaviour not
specific to the jobs (validation, the name-derivation rule, audit) is owned by ADR-022's component
changes; this spec covers the jobs themselves.

## ADDED Requirements

### Requirement: Diff reports every in-scope component with one status

Diff SHALL evaluate the base configuration row of every non-archived component that has VCS
entries and a registry TeamCity link. Archived components, per-range (`vcs.settings` marker) rows
and components without a TeamCity link are left out of the report entirely. Of the evaluated rows,
a multi-root row is always reported; a single-root row is reported when a Checkout Directory or a
non-default Build Working Directory exists on either side (stored or derived), and always when its
status is TC_ERROR, ROOTS_MISMATCH, CONFLICT or UNEXPRESSIBLE (nothing is derived to decide by).
Each reported row gets exactly one status, checked in this order:

- **TC_ERROR**: the TeamCity fetch for the component's configurations failed (a 403 is reported as
  missing permission to read VCS root entries). Reported; never written.
- **ROOTS_MISMATCH**: a non-paused compile configuration that attaches at least one of the row's
  repositories also attaches a repository the registry does not list (for example a shared tooling
  repository). Reported with the extra repositories in its note; never written. The same comparison
  drives the TeamCity Validation finding `VCS_ROOTS_DIFFER_FROM_REGISTRY`.
- **NO_CHAIN**: no compile configuration is found in the projects the component's registry
  TeamCity link names (projects are never searched by `COMPONENT_NAME`). Reported; never written.
- **COMPILE_PAUSED**: configurations are found, but every compile configuration is paused.
  Reported; never written.
- **OUTSIDE_TEMPLATES**: active configurations attach registry roots but none is built on a known
  template (`CDGradleBuild`, `CDJavaMavenBuild`). Reported; never written.
- **UNEXPRESSIBLE**: a checkout rule or `WORK_DIR` value can't be parsed into the shapes ADR-022
  defines. Reported; never written.
- **CONFLICT**: two compile configurations disagree on a repository's checkout rule or `WORK_DIR`,
  or the same repository — matched by full URL including host — is attached twice with different
  rules. Reported; never written.
- **INVALID**: derivation succeeds and disagrees with CRS's stored checkout layout, but the derived
  values fail CRS validation (e.g. a reserved name, or two roots without a Checkout Directory).
  Reported; never offered.
- **IN_SYNC**: derivation succeeds and matches CRS's stored checkout layout exactly. Reported; Sync
  treats a selected IN_SYNC component as a no-op skip.
- **MANUAL_EDIT**: derivation succeeds and disagrees with CRS, and the differing field's last
  audited change is a user write not tagged `sync from TeamCity (job <id>)`. Reported; never
  offered.
- **RESOLVED**: derivation succeeds and disagrees with CRS, the derived values pass CRS validation,
  and every differing field is overwritable — no audit record has ever touched it (still null, or
  holding V8's SQL back-fill, which left no audit record), or its last audited change carries a
  previous Sync's `sync from TeamCity (job <id>)` tag. Offered to Sync.

Every Diff run SHALL be assigned an id. The result of the last *completed* run SHALL be kept and
served, replacing the previous one; a run that is still running or has failed does not replace it.
Diff SHALL keep no history beyond that one result and its id.

#### Scenario: Clean component
- **WHEN** Diff runs and a component's TeamCity-derived checkout layout equals its stored CRS checkout layout
- **THEN** the component is reported IN_SYNC

#### Scenario: First import over a null field
- **WHEN** a component's Build Working Directory is null (no audit record has ever touched it), and
  TeamCity derives a non-empty value that passes CRS validation
- **THEN** the component is reported RESOLVED with both values, and is offered for Sync

#### Scenario: First import over a V8 back-fill
- **WHEN** a component's Checkout Directory holds the value V8's SQL migration wrote (no audit
  record exists for that field, since V8 wrote it directly, not through a v4 write), and TeamCity
  derives a different value that passes CRS validation
- **THEN** the component is reported RESOLVED with both values, and is offered for Sync

#### Scenario: Drifted component, values invalid
- **WHEN** a component's TeamCity-derived checkout layout differs from its stored CRS checkout layout, but the
  derived values would fail CRS validation (e.g. the derived Checkout Directory is a reserved name)
- **THEN** the component is reported INVALID with the derived values and the validation reason, and
  is not offered for Sync

#### Scenario: Component with a manual edit
- **WHEN** a component's stored CRS checkout layout differs from the TeamCity-derived value, and the
  differing field's last audited change was a user write not tagged `sync from TeamCity (job <id>)`
- **THEN** the component is reported MANUAL_EDIT with the stored and derived values, and is not
  proposed for a value change by Sync

#### Scenario: Re-sync after TeamCity changes again
- **WHEN** a component was synced (its checkout layout's last audited change carries a
  `sync from TeamCity (job <id>)` tag), and its TeamCity compile configuration's checkout rule
  changes afterwards
- **THEN** the next Diff reports the component RESOLVED, not MANUAL_EDIT, because the last change
  was a Sync, not a manual user write

#### Scenario: Manual edit after a sync
- **WHEN** a component was synced, and a user then edits its stored CRS checkout layout directly
- **THEN** the next Diff, if TeamCity's derived value differs from that edit, reports the component
  MANUAL_EDIT, naming the user's edit as the reason nothing is proposed

#### Scenario: Archived component
- **WHEN** a component is archived
- **THEN** it does not appear in the Diff report and Sync never touches it

#### Scenario: Marker row
- **WHEN** a component has a per-range `vcs.settings` (marker) row
- **THEN** that row does not appear in the Diff report and is never written by Sync; only the
  component's base row is evaluated

#### Scenario: Extra repository attached by TeamCity
- **WHEN** a component's compile configuration attaches the component's repository and also a
  shared tooling repository the registry does not list
- **THEN** the component is reported ROOTS_MISMATCH, naming the extra repository, is not offered for
  Sync, and TeamCity Validation reports `VCS_ROOTS_DIFFER_FROM_REGISTRY` for it; neither the Diff
  nor Sync adds the extra repository to the registry

#### Scenario: Sync keeps the registry's roots
- **WHEN** Sync writes a RESOLVED component
- **THEN** the component keeps exactly its registry VCS roots, in the same order and with the same
  repository, branch, tag, hotfix branch and repository type; only Checkout Directory, Source Path
  and Build Working Directory change (and a root's name, which the registry derives from its
  Checkout Directory)

#### Scenario: Conflicting compile configurations
- **WHEN** two non-paused compile configurations of a component attach the same repository with
  different checkout rules or different `WORK_DIR` values
- **THEN** the component is reported CONFLICT with both values and is not offered for Sync

#### Scenario: Same repository attached twice
- **WHEN** a component's compile configuration attaches the same repository URL, including host,
  under two different VCS root entries with different checkout rules
- **THEN** the component is reported CONFLICT, the same as two compile configurations disagreeing

#### Scenario: No compile configuration on a known template
- **WHEN** a component has active configurations that attach registry roots with checkout rules,
  but none of them is based on `CDGradleBuild` or `CDJavaMavenBuild`
- **THEN** the component is reported OUTSIDE_TEMPLATES and is not offered for Sync

#### Scenario: Every compile configuration paused
- **WHEN** every compile configuration found for a component is paused
- **THEN** the component is reported COMPILE_PAUSED and is not offered for Sync

#### Scenario: TeamCity fetch fails
- **WHEN** the TeamCity request for a component's configurations errors (timeout, 5xx, or similar)
- **THEN** the component is reported TC_ERROR and is not offered for Sync; other components in the
  same Diff run are unaffected

#### Scenario: Result replaces the previous one
- **WHEN** Diff is run twice and the second run completes
- **THEN** the second run's result and id are what the Portal shows and what Sync can act on; the
  first run's result and id are not retained anywhere

#### Scenario: A failed or running Diff keeps the previous result
- **WHEN** a Diff run completes, and a later run is still running or fails
- **THEN** the completed run's result and id are still served and still accepted by Sync

### Requirement: Diff result is available as an HTML report, a CSV and a JSON document

Diff's latest result SHALL be presented as a human-readable HTML report the Portal opens directly,
and as downloadable CSV and JSON documents, each listing every component and its status.

#### Scenario: Opening the report
- **WHEN** a user who can view components requests the latest Diff report after a run completes
- **THEN** the HTML report of the latest result is returned

#### Scenario: CSV and JSON export
- **WHEN** a user downloads the CSV or the JSON of the latest Diff result
- **THEN** every reported component appears with its status and derived/current values, matching
  the HTML report and each other

### Requirement: Sync is tied to the Diff result it was shown

Sync SHALL require the id of the Diff result the user acted on. If that id no longer matches the
id of the last completed Diff, Sync SHALL refuse the entire request with 409 and error code
`checkout-layout-diff-stale` and write nothing, since the result the user selected components from has
been replaced. Diff keeps no history, so a refused Sync must be followed by a fresh Diff.

#### Scenario: Sync against a replaced Diff
- **WHEN** Diff runs (id D1), then Diff runs again and completes (id D2), and a Sync request names D1
- **THEN** the request is refused in full, nothing is written, and no component is skipped
  individually — the whole request fails before any component is considered

#### Scenario: Sync against the current Diff
- **WHEN** Diff runs (id D1) and a Sync request names D1 before any further Diff runs
- **THEN** the request proceeds to the per-component checks of the requirements below

### Requirement: Sync selection is per component; it writes RESOLVED base rows only

Sync SHALL require `IMPORT_DATA`. Its request SHALL name a Diff id and a list of component ids
(`{diffId, componentIds}`); it SHALL write, for each named component whose status in that Diff
result is RESOLVED, that component's base configuration row only, through the same validation,
name-derivation (ADR-022 decision 4) and audit path v4 writes already use, recording the triggering
user and change metadata `sync from TeamCity (job <id>)` naming this Sync run. A component named
whose status is not RESOLVED (IN_SYNC, MANUAL_EDIT, INVALID, CONFLICT, UNEXPRESSIBLE, NO_CHAIN,
OUTSIDE_TEMPLATES, COMPILE_PAUSED, TC_ERROR or ROOTS_MISMATCH) SHALL be skipped and reported with
its status as the reason; a component's marker (per-range) rows, if any, are never written by Sync.
A user write (create, update, supported versions) whose change comment starts with
`sync from TeamCity` SHALL be rejected with 400, so only Sync can carry the tag that marks a field as
overwritable.

#### Scenario: Selective sync
- **WHEN** a user selects two RESOLVED components out of five reported and runs Sync
- **THEN** only those two components' base rows are written; the other three, including any
  MANUAL_EDIT or INVALID components, are untouched

#### Scenario: Select all resolved
- **WHEN** a user chooses "Select all visible ready" in the Portal (populating `componentIds` with
  every RESOLVED component the current filters show) and runs Sync
- **THEN** every selected RESOLVED component is written; components with any other status are not

#### Scenario: A user write cannot forge the Sync tag
- **WHEN** a user PATCHes a component with a change comment starting `sync from TeamCity`
- **THEN** the write is rejected with 400 and nothing changes

#### Scenario: A named IN_SYNC component is a no-op
- **WHEN** a component named in the Sync request is reported IN_SYNC in the Diff it names
- **THEN** Sync does not write it and reports it as skipped, naming IN_SYNC as the reason

#### Scenario: A named MANUAL_EDIT component is never overwritten
- **WHEN** a component named in the Sync request is reported MANUAL_EDIT in the Diff it names
- **THEN** Sync does not change that component's stored checkout layout and reports it as skipped,
  naming MANUAL_EDIT as the reason

#### Scenario: A named INVALID component is never written
- **WHEN** a component named in the Sync request is reported INVALID in the Diff it names
- **THEN** Sync does not write it and reports it as skipped, naming the validation failure

### Requirement: Sync records enough for a documented manual rollback

Each write Sync makes SHALL be audited with change metadata `sync from TeamCity (job <id>)` naming
this Sync run's own id, and Sync's result SHALL list, per written field, its value before and after
the write, as both CSV and JSON. Rollback is not an endpoint of this capability: it is the manual
procedure documented in `octopus-components-registry-service`'s `docs/registry/technical-design.md`
§6.10, "Rolling back a Sync" — finding the audit rows whose change metadata names a given job id and
PATCHing each field's before-value through v4. Sync's only obligation here is to record enough
(the job id and the before/after values) for that procedure to be followed.

#### Scenario: Sync result lists before/after
- **WHEN** Sync writes a component's Checkout Directory
- **THEN** the Sync result's CSV and its JSON both show that field's value before and after the
  write, and the audit entry for it carries this Sync run's own job id

#### Scenario: Roll back the second of two Syncs
- **WHEN** a component's Build Working Directory is synced by run S1 (before: null, after: A), then
  synced again by run S2 after TeamCity changes further (before: A, after: B), and someone follows
  the documented rollback procedure for job S2
- **THEN** the field is restored to A — S2's own before-value, which happens to be S1's after-value
  — not to null (S1's before-value) and not to V8's original guess, because the procedure PATCHes
  only the audit rows tagged with job S2

### Requirement: Sync detects drift between Diff and the write, per component

Sync SHALL re-read both TeamCity and CRS for each named component immediately before writing, and
SHALL skip a component, reporting it as "changed since diff", when either side no longer matches
the value Diff reported for it. This check is independent of, and in addition to, the whole-request
Diff-id check above.

#### Scenario: CRS changed after Diff
- **WHEN** a component was reported RESOLVED by Diff, and its CRS checkout layout is edited by someone
  else before Sync runs (the Diff id is still current)
- **THEN** Sync skips that component, reports "changed since diff", and writes nothing for it

#### Scenario: TeamCity changed after Diff
- **WHEN** a component was reported RESOLVED by Diff, and its TeamCity compile configuration's
  checkout rule changes before Sync runs (the Diff id is still current)
- **THEN** Sync skips that component, reports "changed since diff", and writes nothing for it

#### Scenario: Unchanged component is written
- **WHEN** a selected RESOLVED component's TeamCity and CRS checkout layout both still match what Diff
  reported
- **THEN** Sync writes the derived checkout layout for it

### Requirement: Running Diff or Sync needs IMPORT_DATA; reading the latest Diff result does not

Starting a Diff run or a Sync run SHALL both require `IMPORT_DATA`, matching the existing "TeamCity
resync" and "TeamCity validation" jobs. Reading the latest Diff result through the registry API —
the HTML report, the CSV or the JSON — SHALL be available to any user who can view components,
whether or not they started that run. (The Portal shows the result on its `/admin` page, which
itself needs `IMPORT_DATA`; Portal issue #239 tracks opening it to viewers.)

#### Scenario: Viewer without IMPORT_DATA can read an existing report
- **WHEN** a user who can view components but lacks `IMPORT_DATA` requests the latest Diff result,
  generated earlier by someone else
- **THEN** the result is returned

#### Scenario: Viewer without IMPORT_DATA cannot start Diff
- **WHEN** a user without `IMPORT_DATA` attempts to start a Diff run
- **THEN** the request is rejected and no run starts

#### Scenario: Sync without IMPORT_DATA
- **WHEN** a user without `IMPORT_DATA` attempts to run Sync
- **THEN** the request is rejected and no component is written
