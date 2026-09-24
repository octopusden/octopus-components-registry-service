## Context

Program-level design: `onb-001-multi-vcs-root-component/design.md` in the program repository.
Baseline characterization tests on branch `test/onb-001-baseline` (`fd47e79d`) pin today's v2
JSON, the DTO shape and the v4 write behaviour this change builds on.

## Decisions

- Migration: next incremental Flyway file (`V8__`): two nullable `varchar` columns on
  `vcs_settings_entries`; `update … set checkout_directory = name where component_configuration_id
  in (select … group by … having count(*) > 1)`. Names are copied verbatim. A row whose names are
  not valid or not distinct Checkout Directories (production: none; QA: checked by the program
  task) stays readable, and its next v4 write fails with 400 until the editor sets valid, distinct
  values; the migration itself never fails on data.
- DSL import (`ImportServiceImpl.attachVcsEntries`) builds entries directly, not through
  `replaceVcsEntries`; it back-fills `checkoutDirectory := name` for rows with more than one entry,
  the migration's rule, and leaves `sourcePath` null. Imported names are not validated (same
  outcome as the migration).
- Validation runs in `replaceVcsEntries`, which every v4 path uses (create, PATCH, field
  overrides, apply-plan via `applyMarkerChildren`), against the final list of the row:
  - more than one entry ⇒ every entry has a `checkoutDirectory`;
  - `checkoutDirectory` matches `^[A-Za-z0-9_][A-Za-z0-9._-]*$` (one segment, no leading dot), is
    unique in the row, and is not `report-templates` or `sonar-config` (fixed in code; no
    configuration property);
  - `sourcePath` is `/`-separated, each segment matches `^[A-Za-z0-9._-]+$` and is not `.` or
    `..` (so it is relative and cannot carry TeamCity rule or parameter syntax);
  - (repository, `sourcePath`) is unique in the row; Git repositories compare case-insensitively,
    matching the model's read-time lower-casing; stored `vcsPath` keeps its case.
- Error shape: `IllegalArgumentException` with message `vcsEntries[<i>].<field>: <reason>`, i.e.
  `{"errorMessage": "vcsEntries[1].checkoutDirectory: required when a row has more than one VCS
  entry"}` through the existing handler: the colon-prefixed single-message form of other v4 rules
  (`distribution: …`), not the `Validation failed: …` bean-validation form. The Portal extends its
  parser to accept the indexed path. The first failing
  rule is reported; `<i>` is the index in the row's list. A duplicate names the later entry.
- Name derivation in `replaceVcsEntries`: `checkoutDirectory` when set; otherwise the stored name
  of the row's only entry when the row had exactly one entry before the write (read before
  `clear()`); otherwise `main`, today's default. No slug derivation. The request `name` is ignored.
- Chain-mismatch warning: emitted when the request carries `vcsEntries` (base configuration or a
  VCS marker row) and the component has a TeamCity project link (`component.versionLines` is not
  empty). No before/after comparison: the Portal sends the VCS slice only when it is dirty. Text:
  "VCS entries changed; the TeamCity build chain no longer matches and must be recreated." Logged
  at INFO with the component name. `warnings` is `[]` on every other response, including GET.
- v2: `VersionControlSystemRoot` (Groovy model) gains the two properties; the DB mapper fills them;
  the Groovy DSL loader leaves them null. `VersionControlSystemRootDTO` appends
  `sourcePath: String? = null, checkoutDirectory: String? = null` and puts `@JvmOverloads` on the
  primary constructor, which keeps the six-parameter JVM constructor for Java/Groovy callers. The
  `copy` signature changes; no consumer's main code calls it (program CRS evidence).

## Risks / Trade-offs

- A v4 client that still sends `name` sees it ignored — documented in the changelog.
- A two-entry row reduced to one entry without `checkoutDirectory` gets the name `main`, not the
  kept entry's old name; escrow then exports it inline, as for any single-root component.
- Per-range VCS marker rows follow the same rules; production has none with more than one entry.

## Migration Plan

Deploy runs the migration. Rollback to the previous release starts on the migrated schema: Spring
Boot 3.2.2 ships Flyway 9 (default `ignoreMigrationPatterns=*:future`, so an applied newer
migration passes validation) and `ddl-auto: validate` ignores the extra columns
(`application-dev-db-automigrate.yml`). The previous release does not keep placement: its
`replaceVcsEntries` recreates a row's entries on any VCS write, dropping both fields for that row.
Names survive (the Portal sends the stored name). Repair runbook, no new code (copied into
`docs/registry/deployment/` at implementation):

1. Before rolling back, snapshot placed entries:
   `select component_configuration_id, sort_order, vcs_path, source_path, checkout_directory from
   vcs_settings_entries where source_path is not null or checkout_directory is not null;`
2. After rolling forward, find multi-entry rows missing a Checkout Directory:
   `select component_configuration_id from vcs_settings_entries group by 1 having count(*) > 1 and
   count(checkout_directory) < count(*);`
3. Repair them with the migration's rule:
   `update vcs_settings_entries set checkout_directory = name where checkout_directory is null and
   component_configuration_id in (select component_configuration_id from vcs_settings_entries group
   by 1 having count(*) > 1);`
4. Re-enter any Source Path or single-root Checkout Directory from the snapshot that is missing now.

See the program design for cross-repository order.
