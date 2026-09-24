## Why

A component can keep its sources in several VCS roots, or in one directory of a repository shared
with other components, but the registry has no way to say where each root's sources go on the
build agent or which part of a shared repository belongs to the component. The build-chain
generator, the Portal and later escrow need that data from the registry. The cross-repository
decision is recorded in the program repository (ADR-001, change
`onb-001-multi-vcs-root-component`); this change is the registry part and merges first.

## What Changes

- VCS entries gain two optional fields, `sourcePath` and `checkoutDirectory`, stored per
  configuration row (base and per-range overrides alike); a blank value is absent.
- Validation on every v4 write that replaces VCS entries: the primary entry (the first) must not
  have a `checkoutDirectory`, since it is checked out at the checkout root, single-entry rows
  included; every secondary entry requires one; `checkoutDirectory` is a single directory name
  without a leading dot and not reserved (`report-templates`, `sonar-config`); the final derived
  names are unique in the row case-insensitively, the primary included; `sourcePath` is relative,
  with plain segments and no `.` or `..`; the pair (repository, `sourcePath`) is unique in the row.
  Errors name the field (`vcsEntries[<i>].<field>: …`).
- `name` becomes derived and read-only: equal to `checkoutDirectory` when set, otherwise the stored
  name of the row's only entry when the row had exactly one entry, otherwise `main`. A `name` in a
  request is ignored.
- A migration adds the two columns and sets `checkoutDirectory := name` for the secondary entries
  (`sort_order > 0`) of existing rows with more than one entry; the primary keeps none. The DSL
  import applies the same back-fill.
- v4 VCS entry request/response carry the fields; the component detail response gains `warnings`,
  carrying a chain-mismatch warning when a request carries base-configuration VCS entries for a
  component with a linked TeamCity project (marker-row writes do not warn).
- Legacy v2 VCS settings carry the fields (omitted when empty); `VersionControlSystemRootDTO` gets
  them as trailing parameters with defaults and `@JvmOverloads`, keeping the six-parameter
  constructor.
- Groovy DSL mode and DSL export do not carry the fields.

## Capabilities

### New Capabilities

- `vcs-root-placement`: storage, validation, derivation and API exposure of VCS entry placement.

### Modified Capabilities

None.

## Impact

- `components-registry-service-core` (published v2 DTO), `component-resolver-api` (VCS root model
  feeding v2), `components-registry-service-server` (schema, entity, v4 DTOs, validation, mappers,
  migration, warning).
- v4 contract change → `docs/registry/api-changelog.md` entry (CI gate); docs updated:
  `functional-spec.md`, `schema-spec.md`, `technical-design.md` as applicable.
- Consumers: build-chain generator, Portal (both merge after this); nine repositories compile the v2
  DTO and are unaffected because the fields are appended with defaults.
