## Why

A component can keep its sources in several VCS roots, or in one directory of a repository shared
with other components, but the registry had no way to say where each root's sources go on the
build agent, which part of a shared repository belongs to the component, or where the build runs.
The build-chain generator therefore attached only the first root, teams edited chains by hand, and
the chains drifted from the registry: releases then tagged, analysed and built incomplete sources.

Decisions: [ADR-022](../../../docs/registry/adr/022-vcs-root-checkout-layout.md) (checkout layout
in the registry) and [ADR-023](../../../docs/registry/adr/023-teamcity-checkout-layout-diff-sync.md)
(importing it from existing TeamCity chains). This change describes the whole feature: the
registry and the integrations that consume it.

## What Changes

- **Registry** (this repository, #503): VCS entries carry `sourcePath` and `checkoutDirectory`;
  configuration rows carry `buildWorkingDirectory`; validation, derived read-only `name`, v4 and
  v2 exposure, a chain-mismatch warning; migrations `V8__`/`V9__`.
- **Checkout layout import** (this repository, #510): two admin jobs, Diff (TeamCity ↔ registry,
  read-only report) and Sync (writes the components a user selects through the v4 write path).
- **Portal** (octopus-components-management-portal #237/#238): edits the three fields on the base
  row and per-range rows, shows `name` read-only, routes field errors and the warning; the admin
  card that runs Diff and Sync.
- **Build-chain generator** (octopus-teamcity-automation #78): one TeamCity VCS root per registry
  root with the checkout rule its layout gives, the root holding the Build Working Directory first,
  default branches from the registry, `WORK_DIR`/`COMPONENT_CONFIG_DIR` and
  `BUILD_VERSION_FORMAT_FILE` when a Build Working Directory is set; unsupported shapes rejected.
- **Sonar automation** (octopus-sonar-automation #24): the target-branch fetch runs in `WORK_DIR`.
- Not changed by any of them: the shared TeamCity templates and their standard steps (the
  version-format parameter of ADR-022 decision 9 is a manual template change), release tooling,
  escrow.

## Capabilities

### New Capabilities

- `vcs-root-checkout-layout`: storage, validation, derivation and API exposure of a VCS entry's
  checkout layout and the Build Working Directory.
- `teamcity-checkout-layout-sync`: the Diff and Sync jobs.
- `build-chain-generation`: how the generator turns registry roots and the Build Working Directory
  into TeamCity VCS roots, checkout rules, attach order, branches and parameters.
- `multi-vcs-root-build-chain`: the end-to-end outcome — registry data alone yields a chain that
  checks out, builds and analyses a multi-root component.

### Modified Capabilities

None.

## Impact

- Order: registry first (schema, contract), then the registry client release; the generator,
  Portal and Sonar automation in any order; the Diff/Sync jobs after the registry rules are live.
- Contracts: additive nullable fields in v2 `VersionControlSystemRootDTO` (`sourcePath`,
  `checkoutDirectory`) and `VCSSettingsDTO` (`buildWorkingDirectory`), appended and omitted when
  null, each keeping its previous constructor as an explicit secondary constructor marked
  `@JsonCreator(mode = DISABLED)`; v4 VCS entries, configuration rows, `warnings` on the detail
  response, the `/admin/teamcity-checkout-layout` endpoints; `docs/registry/api-changelog.md`.
- Data: `V8__` (two nullable entry columns; `checkoutDirectory := name` after the first entry of
  existing multi-entry rows), `V9__` (one nullable row column), `V13__` (event type rename).
- Behaviour changes: a generated root's default branch follows the registry instead of `master`; a
  Git-sourced (Groovy DSL) multi-root component gets no layout in v2, so the generator refuses it
  until it is migrated to the database; a v4 client that adds a second root without a Checkout
  Directory is rejected (400).
- Out of scope: per-root Sonar analysis, escrow, commit path filtering in release tooling, a
  recurring sync, writes back to TeamCity.
