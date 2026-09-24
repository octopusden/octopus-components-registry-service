## Context

Program-level design: `onb-001-multi-vcs-root-component/design.md` in the program repository.
Baseline characterization tests on branch `test/onb-001-baseline` (`fd47e79d`) pin today's v2
JSON, the DTO shape and the v4 write behaviour this change builds on.

## Decisions

- Migration: next incremental Flyway file (`V8__`): two nullable `varchar` columns on
  `vcs_settings_entries`; `update vcs_settings_entries set checkout_directory = name where
  sort_order > 0`. `sort_order` is `0..n-1` in every row (production check Q6; `replaceVcsEntries`
  writes the list index), so this reaches exactly the secondary entries of multi-entry rows; the
  primary keeps a null Checkout Directory. Names are copied verbatim. A row whose secondary names
  are not valid Checkout Directories, or whose final names would not be distinct (production:
  none; QA: 0 duplicate names, checked by the program task), stays readable, and its next v4 write
  fails with 400 until the editor sets valid, distinct values; the migration itself never fails on
  data.
- DSL import (`ImportServiceImpl.attachVcsEntries`) builds entries directly, not through
  `replaceVcsEntries`; it back-fills `checkoutDirectory := name` for the secondary entries
  (index > 0) of rows with more than one entry, the migration's rule, and leaves `sourcePath` null. Imported names are not validated (same
  outcome as the migration).
- Blank values: `sourcePath` and `checkoutDirectory` are trimmed and a blank value is stored as
  null before validation, so `""` means absent rather than failing the regex.
- Validation runs in `replaceVcsEntries`, which every v4 path uses (create, PATCH, field
  overrides, apply-plan via `applyMarkerChildren`), against the final list of the row:
  - the entry at index 0 (the primary, checked out at the checkout root) has no
    `checkoutDirectory`, whatever the row's size; every entry at index > 0 has one;
  - `checkoutDirectory` matches `^[A-Za-z0-9_][A-Za-z0-9._-]*$` (one segment, no leading dot) and
    is not `report-templates` or `sonar-config` (fixed in code; no configuration property);
  - the final derived names (see below) are unique in the row, compared case-insensitively, the
    primary included; the primary comes first and has no Checkout Directory, so a collision is
    always reported on the later entry's `checkoutDirectory`, e.g. a secondary `main` next to a
    primary named `main`;
  - `sourcePath` is `/`-separated, each segment matches `^[A-Za-z0-9._-]+$` and is not `.` or
    `..` (so it is relative and cannot carry TeamCity rule or parameter syntax);
  - (repository, `sourcePath`) is unique in the row; Git repositories compare case-insensitively,
    matching the model's read-time lower-casing; stored `vcsPath` keeps its case.
- Error shape: `IllegalArgumentException` with message `vcsEntries[<i>].<field>: <reason>`, i.e.
  `{"errorMessage": "vcsEntries[1].checkoutDirectory: required on a secondary VCS entry"}` through the existing handler: the colon-prefixed single-message form of other v4 rules
  (`distribution: …`), not the `Validation failed: …` bean-validation form. The Portal extends its
  parser to accept the indexed path. The first failing
  rule is reported; `<i>` is the index in the row's list. A duplicate (repository, `sourcePath`)
  names the later entry's `sourcePath`; a duplicate name names the later entry's
  `checkoutDirectory`. A multi-row request (PATCH with `fieldOverrides`, applied row by row in
  `applyFieldOverrideDesiredSet` → `applyMarkerChildren`) fails on the first failing row. In `applyFieldOverrideDesiredSet` an
  `IllegalArgumentException` whose message starts with `vcsEntries[` is rethrown as
  `fieldOverrides[<j>].<message>`, `<j>` being the row's index in the request list (captured before
  the create/update split); other row errors keep their shape. The Portal re-sends every
  override row, so without the prefix an error on an untouched migrated row could not be routed.
  The field-override endpoints (one row per request) use the unprefixed form.
- Name derivation in `replaceVcsEntries`: a secondary entry's name is its `checkoutDirectory`; the
  primary's is the stored name of the row's previous `sort_order`-0 entry (read before `clear()`)
  when the row had entries before the write; otherwise `main`, today's default. No slug
  derivation. The request `name` is ignored.
- Chain-mismatch warning: emitted when the request carries base-configuration `vcsEntries` and the
  component has a TeamCity project link (`component.versionLines` is not empty). No before/after
  comparison: the Portal sends the base VCS slice only when it is dirty. Marker-row writes do not
  warn: the field-override endpoints return `FieldOverrideResponse`, which has no `warnings`, and a
  Portal PATCH re-sends the full override set in `fieldOverrides` whenever any override changed,
  so a marker row's presence says nothing about a VCS change. Text:
  "VCS entries changed; the TeamCity build chain no longer matches and must be recreated." Logged
  at INFO with the component name. `warnings` is `[]` on every other response, including GET.
- v2: `VersionControlSystemRoot` (Groovy model) gains the two properties; the DB mapper fills them;
  the Groovy DSL loader leaves them null. `VersionControlSystemRootDTO` appends
  `sourcePath: String? = null, checkoutDirectory: String? = null` and puts `@JvmOverloads` on the
  primary constructor, which keeps the six-parameter JVM constructor for Java/Groovy callers. The
  `copy` signature changes; no consumer's main code calls it (program CRS evidence).

## Risks / Trade-offs

- A v4 client that still sends `name` sees it ignored — documented in the changelog.
- The primary's name is stable by rule: it carries over from the previous primary on every write,
  so migrated rows keep their names and escrow layout. A row reduced to one entry keeps the previous
  primary's name; escrow then exports it inline, as for any single-root component. If the kept
  entry was a secondary, it takes the previous primary's name and its `checkoutDirectory` must be
  cleared (the Portal sends `null`), since it is now the primary.
- A secondary Checkout Directory can equal a directory of the primary repository, which then
  holds both. The registry cannot see repository content; this is a documented residual, and the
  generator logs each placement.
- A per-range VCS marker row change does not warn, although it can also leave the chain out of
  date.
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
2. After rolling forward, find secondary entries missing a Checkout Directory:
   `select component_configuration_id, sort_order from vcs_settings_entries where sort_order > 0
   and checkout_directory is null;`
3. Repair them with the migration's rule, secondary entries only:
   `update vcs_settings_entries set checkout_directory = name where checkout_directory is null and
   sort_order > 0;`
4. Re-enter any Source Path from the snapshot that is missing now. An entry added during the
   rollback window is named `main`; if it is a secondary and its row's primary is also `main`, or
   another secondary is, step 3 yields a duplicate name, so set that entry's Checkout Directory by
   hand. The previous release never writes a Checkout Directory, so no primary gets one there.

See the program design for cross-repository order.
