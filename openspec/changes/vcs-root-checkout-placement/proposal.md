## Why

A component can keep its sources in several VCS roots, or in one directory of a repository shared
with other components, but the registry has no way to say where each root's sources go on the
build agent or which part of a shared repository belongs to the component. The build-chain
generator, the Portal and later escrow need that data from the registry. The cross-repository
decision is recorded in the program repository (ADR-001, change
`onb-001-multi-vcs-root-component`); this change is the registry part and merges first.

## What Changes

- VCS entries gain two optional fields, `sourcePath` and `checkoutDirectory`, stored per
  configuration row (base and per-range overrides alike).
- Validation on every v4 write that replaces VCS entries: a configuration row with more than one
  entry requires a `checkoutDirectory` on each; `checkoutDirectory` is a single directory name, not
  `.`/`..`, unique in the row and not reserved; `sourcePath` is relative without empty, `.` or `..`
  segments; the pair (repository, `sourcePath`) is unique in the row.
- `name` becomes derived and read-only: equal to `checkoutDirectory` when set, otherwise the stored
  name of a single entry is kept, and a new entry gets the repository slug. A `name` in a request is
  ignored.
- A migration adds the two columns and sets `checkoutDirectory := name` for every entry of existing
  rows with more than one entry.
- v4 VCS entry request/response carry the fields; the component detail response gains `warnings`,
  carrying a chain-mismatch warning when entries change on a component with a linked TeamCity
  project.
- Legacy v2 VCS settings carry the fields (omitted when empty); `VersionControlSystemRootDTO` gets
  them as trailing parameters with defaults, keeping the six-parameter constructor.
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
