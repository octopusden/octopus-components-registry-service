## Why

A component can keep its sources in several VCS roots, or in one directory of a repository shared
with other components, but the registry has no way to say where each root's sources go on the
build agent, which part of a shared repository belongs to the component, or where the build runs.
The build-chain generator, the Portal and later escrow need that data from the registry. The
cross-repository decision is recorded in the program repository (ADR-001 revision 3, change
`onb-001-multi-vcs-root-component`); this change is the registry part and merges first.

Revision 3 replaces revision 2's "first entry always at the checkout root": any entry may be
placed in a Checkout Directory, and where the build runs is its own field.

## What Changes

- VCS entries carry two optional fields, `sourcePath` and `checkoutDirectory`, stored per
  configuration row (base and per-range overrides alike); a blank value is absent.
- Configuration rows that carry VCS entries (the base row and per-range VCS rows) gain an optional
  `buildWorkingDirectory`: a relative path, empty = the checkout root.
- Validation on every v4 write that replaces VCS entries or sets `buildWorkingDirectory`: at most
  one entry of a row without `checkoutDirectory` (it is checked out at the checkout root);
  `checkoutDirectory` is a single directory name without a leading dot and not reserved
  (`report-templates`, `sonar-config`, `target`, `sonar-report`, ignoring case); the final derived
  names are unique in the row case-insensitively; `sourcePath` and `buildWorkingDirectory` are
  relative, with plain segments and no `.` or `..`; each field is at most 255 characters; the pair
  (repository, `sourcePath`) is unique in the row; `buildWorkingDirectory` starts inside a placed
  entry, and is required when every entry of the row has a `checkoutDirectory`. Errors name the field (`vcsEntries[<i>].<field>: …`, `buildWorkingDirectory: …`).
- `name` is derived and read-only: an entry with a `checkoutDirectory` is named by it; an entry
  without one keeps the stored name of the row's previous entry with the same repository, else
  `main`. A `name` in a request is ignored.
- Migrations: `V8__` (shipped, applied on QA, unchanged) adds the two entry columns and sets
  `checkoutDirectory := name` after the first entry of existing multi-entry rows; the DSL import
  applies the same back-fill. `V9__` adds the nullable `build_working_directory` column.
- v4 VCS entry request/response carry the entry fields; the base configuration request/response and
  the `vcs.settings` marker payload carry `buildWorkingDirectory`; the component detail response
  gains `warnings`, carrying a chain-mismatch warning when a request carries base-configuration
  `vcsEntries` or `buildWorkingDirectory` for a component with a linked TeamCity project.
- Legacy v2: `VersionControlSystemRootDTO` carries the entry fields and `VCSSettingsDTO` carries
  `buildWorkingDirectory`, all appended with defaults and omitted when empty; each keeps its
  previous constructor as an explicit secondary constructor marked `@JsonCreator(mode = DISABLED)`.
- Groovy DSL mode and DSL export do not carry the fields.

## Capabilities

### New Capabilities

- `vcs-root-placement`: storage, validation, derivation and API exposure of VCS entry placement and
  the Build Working Directory.

### Modified Capabilities

None.

## Impact

- `components-registry-service-core` (published v2 DTOs), `component-resolver-api` (VCS model
  feeding v2), `components-registry-service-server` (schema, entities, v4 DTOs, validation,
  mappers, migrations, warning).
- v4 contract change → `docs/registry/api-changelog.md` entry (CI gate); docs updated:
  `functional-spec.md`, `schema-spec.md`, the rollback runbook in `docs/registry/deployment/`.
- The open migration PR #481 renumbers to `V10__`.
- Consumers: build-chain generator, Portal, Sonar automation and the program's one-off placement
  import (all after this); nine repositories compile the v2 DTOs and are unaffected because the
  fields are appended with defaults.
