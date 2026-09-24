## Context

Program-level design: `onb-001-multi-vcs-root-component/design.md` in the program repository.
Baseline characterization tests on branch `test/onb-001-baseline` pin today's v2 JSON, the DTO
shape and the v4 write behaviour this change builds on.

## Decisions

- Migration: next incremental Flyway file (after `V7__`): two nullable `varchar` columns on
  `vcs_settings_entries`; `update … set checkout_directory = name where component_configuration_id
  in (select … group by … having count(*) > 1)`.
- Validation lives with the existing v4 cross-field validation and reports field-prefixed 400
  errors (`vcsEntries[i].checkoutDirectory`, `vcsEntries[i].sourcePath`), like other v4 rules.
- Reserved Checkout Directory names: `report-templates`, `sonar-config`, `.sonar-automation` in
  code, plus `components-registry.vcs.reserved-checkout-directories` (list, default empty) for
  deployment-specific helper-clone directories, whose values must not be committed.
- Git repository comparison for the (repository, `sourcePath`) rule is case-insensitive, matching
  the model's existing read-time lower-casing for Git; stored `vcsPath` keeps its case.
- Name derivation happens where entries are rebuilt from the request (`replaceVcsEntries`); the
  single-entry "keep stored name" case reads the name of the only existing entry of that row.
- Chain-mismatch warning: computed by comparing the set of (repository, `sourcePath`,
  `checkoutDirectory`) per row before and after the write; emitted only when a TeamCity project is
  linked to the component. Text: "VCS entries changed; the TeamCity build chain no longer matches
  and must be recreated."
- v2: `VersionControlSystemRoot` (Groovy model) gains the two properties; the DB mapper fills them;
  the Groovy DSL loader leaves them null. `VersionControlSystemRootDTO` appends
  `sourcePath: String? = null, checkoutDirectory: String? = null` with an explicit six-parameter
  secondary constructor.

## Risks / Trade-offs

- A v4 client that still sends `name` sees it ignored — documented in the changelog.
- Per-range override rows carrying VCS entries are validated independently; the production data has
  none with more than one entry.

## Migration Plan

Deploy runs the migration; rollback to the previous release leaves the nullable columns in place
(ignored by the previous code). See the program design for cross-repository order.
