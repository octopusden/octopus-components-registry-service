## Context

Program-level design: `onb-001-multi-vcs-root-component/design.md` in the program repository
(ADR-001 revision 3). Baseline characterization tests on branch `test/onb-001-baseline`
(`fd47e79d`) pin today's v2 JSON, the DTO shape and the v4 write behaviour this change builds on.
Revision 2 is implemented on this branch and deployed to QA (V8 applied there); revision 3 changes
the placement rules and the name rule, and adds the Build Working Directory.

## Decisions

- Migrations. `V8__add_vcs_entry_placement.sql` stays as shipped (Flyway migrations are immutable
  once applied): two nullable `varchar(255)` columns on `vcs_settings_entries`, and
  `checkout_directory = name where sort_order > 0`. Under revision 3 this is a valid fallback
  (every row keeps exactly one entry without Checkout Directory, the first); the program's
  placement import replaces it where TeamCity shows the real layout. New
  `V9__add_build_working_directory.sql`: `alter table component_configurations add column
  build_working_directory varchar(255);`, no data change. The open PR #481 becomes `V10__`.
- DSL import (`ImportServiceImpl.attachVcsEntries`, `ImportServiceImpl.kt:982,1854`) keeps
  revision 2's back-fill (`checkoutDirectory := name` for index > 0 of multi-entry rows,
  `sourcePath` null) and leaves `buildWorkingDirectory` null.
- Blank values: `sourcePath`, `checkoutDirectory` and `buildWorkingDirectory` are trimmed and a
  blank value is stored as null before validation, so `""` means absent rather than failing the
  regex.
- v4 shape of the Build Working Directory. It belongs to the row that supplies a version's VCS
  entries, so it travels with them: `BaseConfigurationRequest.buildWorkingDirectory` and
  `ComponentConfigurationResponse.buildWorkingDirectory` for the base row, and
  `MarkerChildrenPayload.buildWorkingDirectory` for a `vcs.settings` marker row
  (`FieldOverrideRequest.kt:124-132`; `rejectExtraneousMarkerFields` accepts it only next to
  `vcsEntries`). In the base PATCH, `null` leaves the stored value, blank clears it; a marker
  payload replaces the row's children, so a missing value clears it.
- Validation runs in `replaceVcsEntries` (`ComponentManagementServiceImpl.kt:2709`), which every v4
  path uses (create, PATCH, field overrides, apply-plan via `applyMarkerChildren` at `:2912`), and
  also when only the base `buildWorkingDirectory` changes, against the row's final entries and
  final Build Working Directory. Revision 2's `checkoutDirectoryError(primary, dir)` loses its
  first two branches (`:2775-2776`); the rest stays:
  - at most one entry has no `checkoutDirectory`; a second one fails on its `checkoutDirectory`
    ("required: vcsEntries[<k>] is already checked out at the checkout root");
  - `checkoutDirectory` matches `^[A-Za-z0-9_][A-Za-z0-9._-]*$` (one segment, no leading dot), is at
    most 255 characters, and is not `report-templates`, `sonar-config`, `target` or `sonar-report`,
    compared ignoring case (`RESERVED_CHECKOUT_DIRECTORIES`, `:4615`);
  - the final derived names (see below) are unique in the row, compared case-insensitively; a
    collision is reported on the later entry's `checkoutDirectory`;
  - `sourcePath` is at most 255 characters, `/`-separated, each segment matches
    `^[A-Za-z0-9._-]+$` and is not `.` or `..`;
  - (repository, `sourcePath`) is unique in the row; Git repositories compare case-insensitively,
    matching the model's read-time lower-casing; stored `vcsPath` keeps its case;
  - `buildWorkingDirectory` has the `sourcePath` shape, and either its first segment equals (case
    sensitively, it is a path on the agent) the `checkoutDirectory` of an entry of the row, or the
    row has an entry without `checkoutDirectory`. A row without entries therefore cannot have one.
  - a row with entries that all have a `checkoutDirectory` requires a `buildWorkingDirectory`
    (empty means the checkout root, valid only when an entry is checked out there): "required when
    every VCS entry has a Checkout Directory".
- Error shape: `IllegalArgumentException` with message `vcsEntries[<i>].<field>: <reason>` or
  `buildWorkingDirectory: <reason>`, through the existing handler as
  `{"errorMessage": "…"}`: the colon-prefixed single-message form of other v4 rules. The first
  failing rule is reported; entry rules are checked before the Build Working Directory. In a
  component PATCH with `fieldOverrides`, `withFieldOverrideIndex` (`:2261-2270`) prefixes
  `fieldOverrides[<j>].` to messages starting `vcsEntries[` and now also `buildWorkingDirectory:`;
  other row errors keep their shape. The field-override endpoints (one row per request) use the
  unprefixed form.
- Name derivation in `replaceVcsEntries`, replacing revision 2's "previous `sort_order` 0 entry"
  (`:2716`): an entry with `checkoutDirectory` is named by it; an entry without one takes the stored
  name of the row's previous entry with the same repository (compared as in the uniqueness rule;
  when the repository appeared twice, a previous entry without Checkout Directory wins, then the
  lowest `sort_order`), read from `config.vcsEntries` before `clear()`; otherwise `main`. A kept
  name equal (ignoring case) to a Checkout Directory of the new list is dropped for `main`, so an
  echo of one repository placed and at the root, or a Checkout Directory moved to another
  repository, does not collide. No slug derivation. The request `name` is ignored. So an
  entry keeps its name while it stays at the checkout root on the same repository; re-pointing it
  to another repository names it `main`; moving an entry out of a Checkout Directory to the root
  keeps the name it had (its old Checkout Directory).
- Chain-mismatch warning (`withVcsChainWarning`, `:3550-3557`): emitted when the request carries
  base-configuration `vcsEntries` or `buildWorkingDirectory` and the component has a TeamCity
  project link (`component.versionLines` is not empty). No before/after comparison. Marker-row
  writes do not warn (the field-override endpoints return `FieldOverrideResponse`, which has no
  `warnings`, and a Portal PATCH re-sends every override row). Logged at INFO with the component
  name. `warnings` is `[]` on every other response, including GET.
- v2. `VersionControlSystemRoot` (Groovy model) carries the entry fields and `VCSSettings` gains
  `buildWorkingDirectory`; the DB mapper (`EntityMappers.kt:1126-1143`, `toVCSSettings`) fills it
  from the row whose entries it maps; the Groovy DSL loader leaves it null.
  `ComponentControllerV2.resolveVCSSettings` (`:59-78`) and `VCSSettings.toDTO()`
  (`Mappers.kt:63-69`) pass it on. `VCSSettingsDTO` (read by consumers through
  `DetailedComponent.vcsSettings`) appends `buildWorkingDirectory: String? = null` with a
  property-level `@JsonInclude(NON_NULL)`, so the other properties serialize as today, and keeps a
  two-parameter JVM constructor as an explicit secondary constructor marked
  `@JsonCreator(mode = DISABLED)`, the pattern `VersionControlSystemRootDTO` already uses for its
  six-parameter constructor. Not `@JvmOverloads`: the overloads copy each parameter's
  `@JsonProperty`, and Jackson without the Kotlin module then fails with "Conflicting
  property-based creators".
- `VCSSettings.groovy` `equals`, `hashCode` and `toString` (`:72-90`) include
  `buildWorkingDirectory`, so two settings that differ only in it are not equal.
- DSL export omits `buildWorkingDirectory`, like the entry fields; the git-vs-db comparison records
  it as a known difference. Compat known-delta (`docs/registry/api-compat-deltas.md:46-56`,
  rewritten for revision 3): a Checkout Directory is now legal on any root, `[0]` included, so the
  raw-layer `known-deltas-db.json` pattern widens from `\[[1-9]\d*\]` to `\[\d+\]` on
  `versionControlSystemRoots[…].checkoutDirectory` and `.sourcePath`, the typed comparator in
  `Comparators.buildAssertion` forgives baseline-null → value on those fields at any index, and
  `buildWorkingDirectory` on the VCS settings gets the same baseline-null → value rule; a changed
  or dropped value stays a VALUE_DIFF.

## Risks / Trade-offs

- A v4 client that still sends `name` sees it ignored — documented in the changelog.
- An entry moving into a Checkout Directory is renamed to it, which changes the escrow export
  directory; an entry at the checkout root keeps its name, so the import renames only entries it
  moves.
- A Checkout Directory can equal a directory of the repository at the checkout root, which then
  holds both. The registry cannot see repository content; documented residual, the generator logs
  each placement.
- A per-range VCS marker row change does not warn, although it can also leave the chain out of
  date. Production has no marker row with more than one entry.

## Migration Plan

Deploy runs V9 (V8 is already applied where revision 2 was deployed). Rollback to the release
before this change starts on the migrated schema: Spring Boot 3.2.2 ships Flyway 9 (default
`ignoreMigrationPatterns=*:future`) and `ddl-auto: validate` ignores the extra columns. That release
never writes `build_working_directory`, so the column keeps its values unless a row is deleted. It
does drop the entry placement of any row whose VCS entries it writes. Rolling QA back to the
revision 2 image instead makes rows with a Checkout Directory on their first entry fail their next
v4 write until rolled forward.

Repair runbook (`docs/registry/deployment/vcs-placement-rollback.md`, updated for revision 3; no
new code):

1. Before rolling back, snapshot placement:
   `select component_configuration_id, sort_order, vcs_path, source_path, checkout_directory from
   vcs_settings_entries where source_path is not null or checkout_directory is not null;`
2. After rolling forward, restore `source_path` and `checkout_directory` from the snapshot by
   (`component_configuration_id`, `vcs_path`, `sort_order`); `sort_order` is part of the key
   because one repository may appear twice in a row (Q25). V8's rule is not a repair any more: under revision 3
   a later entry may legitimately have no Checkout Directory.
3. Rows written during the window whose entries are not in the snapshot keep what that release
   wrote; if such a row has more than one entry without Checkout Directory, its next v4 write fails
   with 400 until an editor places the entries.

See the program design for cross-repository order.
