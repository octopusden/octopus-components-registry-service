# API v4 Changelog

Changelog for consumers of the **v4** REST API (CRUD + audit + admin), the contract the
components-management Portal SPA binds to. v1/v2/v3 are separate stable read contracts and are
not covered here.

The machine-readable contract is generated from the v4 controllers and committed at
[`components-registry-service-server/src/main/resources/openapi/v4.json`](../../components-registry-service-server/src/main/resources/openapi/v4.json).
It is published by the TeamCity `[1.0]` build under the `openapi/` artifact path and consumed by
the Portal (`frontend/src/lib/api/v4.json` → `schema.d.ts`). A CI drift gate
(`OpenApiV4SpecTest`) fails the build if the committed spec disagrees with the live regeneration;
refresh it with `./gradlew :components-registry-service-server:generateOpenApiDocs`. See
[TD-003](tech-debt/003-openapi-v4-spec-generation.md).

> **How to update:** when a PR changes the v4 surface (a new field, endpoint, enum value, or a
> rename/removal), run `generateOpenApiDocs`, commit the refreshed `v4.json`, and add a dated
> entry below describing the consumer-visible change.

## Unreleased

- **`GET /meta/release-managers` and `GET /meta/security-champions` added.** Two new
  `ACCESS_COMPONENTS` endpoints return the sorted, distinct release-manager and security-champion
  usernames currently assigned to at least one component the v4 list shows (blank values excluded), as
  `List<String>` like `/meta/owners`. They list the values the existing `?releaseManager=` and
  `?securityChampion=` filters can match; each list holds only its own role.
- **TeamCity placement Diff/Sync admin endpoints (ONB-002).** New surface under
  `rest/api/4/admin/teamcity-placement`, merging right after the VCS entry placement fields below
  (it reads and writes `sourcePath`/`checkoutDirectory`/`buildWorkingDirectory`):
  - `POST /diff` / `GET /diff/job` — start (`IMPORT_DATA`) and poll a read-only run that derives
    each component's VCS placement from its linked TeamCity project(s)' compile build
    configurations and compares it to the registry's current values. 202 on a freshly-started run,
    409 on a same-kind attach or a cross-kind conflict with another admin job, same shape as every
    other admin job (`TeamcityPlacementDiffJobResponse`, `kind: "job"`).
  - `GET /diff/report.json` / `.../report.html` / `.../report.csv` — the latest completed run's
    rows (`PlacementDiffResult`, whose `diffId` names the Diff run — the id a Sync request must
    send), readable by anyone who can view components (no `IMPORT_DATA`
    needed for the report itself). 404 until a Diff has completed at least once. Per row: status
    (`RESOLVED`, `INVALID`, `CONFLICT`, `UNEXPRESSIBLE`, `NO_CHAIN`, `OUTSIDE_TEMPLATES`,
    `COMPILE_PAUSED`, `MANUAL_EDIT`, `IN_SYNC`, `TC_ERROR`, `ROOTS_MISMATCH`), current and derived
    Checkout Directory / Source Path per VCS entry, current and derived Build Working Directory,
    the source TeamCity build type ids, and human-readable notes. `INVALID`: the derived values
    parse fine but fail the SAME CRS validation a v4 write runs (Source Path shape, reserved/
    duplicate Checkout Directory names, at most one root at the checkout root, name uniqueness,
    Build Working Directory rules); `notes` carries the validation message. `UNEXPRESSIBLE` is
    narrower now: only a checkout-rule or `WORK_DIR` SHAPE that can't be parsed at all (a remap,
    several rules on one entry, `%VAR%`) — a value that parses but fails CRS validation is
    `INVALID` instead. A repository attached twice within ONE build type with two different
    RESOLVABLE rules is `CONFLICT` (same as two build types disagreeing), not `UNEXPRESSIBLE`.
    `ROOTS_MISMATCH` (new): a BASE row whose compile configurations attach VCS roots the registry
    does not list (typically a shared tooling repository) — only configurations that attach at
    least one of the component's own repositories are judged, so siblings in a shared project and
    old version lines on another project are not flagged; `notes` names the repository and build
    type ids. Takes precedence over `INVALID` and the derived-value checks, is never `RESOLVED` and
    never offered to Sync. The same comparison backs the new TeamCity Validation type
    `VCS_ROOTS_DIFFER_FROM_REGISTRY` (severity `WARNING`; reports extra roots and registry roots no
    compile configuration attaches), a new `type` value on `teamcity-validations` findings.
    Only the current (BASE) configuration of non-archived components is diffed; archived components
    and version-range (`vcs.settings`) rows are not in the report. A single-root row needing nothing
    is left out too, except `CONFLICT` and `UNEXPRESSIBLE` rows, which are always reported. Repository matching is by the full canonical VCS URL, host included
    (previously host-agnostic, a false-positive-match risk across TeamCity hosts).
  - `POST /sync` (`IMPORT_DATA`, body `{"diffId": "...", "componentIds": [...]}`) / `GET /sync/job`
    — applies the named Diff's `RESOLVED` rows for the given components, re-deriving first and
    skipping any row that changed since the Diff snapshot. **`diffId` is now required**: if it does
    not match the latest COMPLETED Diff, the whole request is refused with `409` and nothing is
    written (Diff keeps no history, so a stale `diffId` means the result the caller saw has been
    replaced — run Diff again; a `diffId` matching a still-RUNNING Diff is refused the same way).
    Writes go through the same v4 write path a human PATCH uses, tagging `changeComment` as
    `"sync from TeamCity (job <jobId>)"` — the Sync run's own id, so its audit rows can be selected
    for rollback. That tag is reserved: `POST /components`, `PATCH /components/{id}` and
    `PUT /components/{id}/supported-versions` now answer `400` when a user-supplied `changeComment`
    starts with `sync from TeamCity` (case-insensitive, after trim). A value the
    ADR-001 `V8__` migration set automatically, or one whose last audited change was a Sync itself,
    is overwritable; a value set by a real user edit never is (re-syncing after TeamCity changes
    again no longer gets permanently stuck reporting `MANUAL_EDIT` against Sync's own prior write) —
    Checkout Directory and Source Path are tracked independently for this, so a Sync write to one
    field never "covers" a manual edit of the other. `PlacementSyncResult` gains `fieldChanges`: one
    entry per field actually written (`componentKey`, `rowLabel`, `root`, `field`, `before`,
    `after`) — the rollback trace, also available as `GET /sync/report.csv` (`IMPORT_DATA`, same
    shape as the Diff CSV).

- **VCS entry placement (`sourcePath`, `checkoutDirectory`), Build Working Directory and derived
  names.** `VcsEntryRequest` / `VcsEntryResponse` (base configuration and `vcs.settings` marker rows
  alike) gain two optional fields: `sourcePath`, the repository directory that belongs to the
  component, and `checkoutDirectory`, the directory an entry is checked out to on the build agent.
  `BaseConfigurationRequest`, `ComponentConfigurationResponse` and the `vcs.settings`
  `MarkerChildrenPayload` gain `buildWorkingDirectory`: where the build runs, relative to the
  checkout root (other markers reject it). In a base PATCH `null` leaves it unchanged and `""` clears
  it; a marker payload replaces the row, so an absent value clears it. A blank value is stored as
  absent. Every write that replaces a row's VCS entries or sets its `buildWorkingDirectory`
  validates the final row and fails with `400` and `errorMessage` `vcsEntries[<i>].<field>: <reason>`
  when: a second entry has no `checkoutDirectory` (only one entry can be at the checkout root); a
  `checkoutDirectory` or `sourcePath` is longer than 255 characters; a `checkoutDirectory` is not one
  segment matching `^[A-Za-z0-9_][A-Za-z0-9._-]*$` or is `report-templates`, `sonar-config`,
  `target` or `sonar-report` (ignoring case); two entries end up with the same name, compared
  case-insensitively (reported on the later entry's `checkoutDirectory`); a `sourcePath` segment is
  empty, `.`, `..` or does not match `^[A-Za-z0-9._-]+$`; or two entries share repository (Git
  ignoring case) and `sourcePath` (reported on the later entry's `sourcePath`). Entry rules come
  first; then `buildWorkingDirectory: <reason>` when it is longer than 255 characters, has a segment
  that is empty, `.`, `..` or does not match `^[A-Za-z0-9._-]+$`, starts outside every entry's
  `checkoutDirectory` (case-sensitive) while no entry is at the checkout root, or is missing while
  every entry has a `checkoutDirectory`. In a component PATCH the error of a `fieldOverrides` row is
  prefixed with its index (`fieldOverrides[<j>].vcsEntries[<i>].…`, `fieldOverrides[<j>].buildWorkingDirectory: …`);
  the field-override endpoints use the unprefixed form. **`name` in a request is now ignored**: an
  entry with a `checkoutDirectory` is named by it, one without keeps the stored name of the row's
  previous entry on the same repository, else `main`. **`ComponentDetailResponse.warnings`** (list of
  strings, `[]` by default, also on GET) is added; a create or PATCH that carries
  `baseConfiguration.vcsEntries` or `baseConfiguration.buildWorkingDirectory` for a component with a
  linked TeamCity project returns `"VCS entries changed; the TeamCity build chain no longer matches
  and must be recreated."`. Marker-row writes do not warn. `V8__` sets `checkoutDirectory = name`
  after the first entry of existing multi-entry rows; `V9__` adds the Build Working Directory. A
  migrated row whose names are not valid or not distinct checkout directories fails its next VCS save
  with `400` until corrected. The legacy v2 VCS settings carry all three fields, omitted when empty.
  The text after the colon is written for editors ("VCS root N", 1-based, the repository name, what
  to change, an example value) and may be reworded; clients route on the prefix before the colon
  only.
  Binary note for Kotlin consumers of the published v2 DTOs: `VersionControlSystemRootDTO` and
  `VCSSettingsDTO` keep their previous JVM constructors (six and two parameters), but their `copy`
  methods and default-argument constructors change signature, so Kotlin code calling them must be
  recompiled against the new version; Java and Groovy callers of the previous constructors are
  unaffected.

- **`genericArtifacts` added to component configurations (SYS-098).** Configuration read responses
  (`ComponentConfigurationResponse`) gain a required `genericArtifacts: GenericArtifactResponse[]`
  field (always present, empty when none are set). Configuration write requests
  (`ComponentConfigurationCreateRequest`, `ComponentConfigurationPatchRequest`) accept an optional
  `genericArtifacts: GenericArtifactRequest[]`. New schemas:
  `GenericArtifactRequest { path: string }` — the storage path, which may contain the `${version}`
  template resolved at distribution time; and
  `GenericArtifactResponse { id: uuid, path: string, sortOrder: int32 }` — the persisted artifact
  with its stable identity and display order. Purely additive on the read side; clients that ignore
  the new field are unaffected. The write side accepts but does not require the field.

- **`GET /rest/api/4/components/{idOrName}/archive-readiness` added.** Read-only pre-flight check
  for the archive/delete flow, gated by the same authorization as `deleteComponent`
  (`ACCESS_COMPONENTS` + `canDeleteComponent`). Returns `{ready: Boolean, entries: [...]}`: one
  entry per external target the component uses (VCS repository, TeamCity project, Jira open
  issues, Jira project) with an `outcome` of `COMPLETED` / `NOT_COMPLETED` / `UNKNOWN`, an optional
  `reason` and `reasonKind` (`SYSTEM_UNAVAILABLE` / `REGISTRY_DATA` / `NOT_CONFIGURED` — classifies
  what would resolve an `UNKNOWN` entry), `sharedWith` (other live components still using the same
  target), and `openIssues` (JIRA_ISSUES only). `ready` is `false` if any entry is `NOT_COMPLETED`
  or `UNKNOWN` — callers should gate on `ready`, not derive it from `entries` themselves. A VCS
  repository or TeamCity project the external system reports absent needs to be genuinely
  confirmed gone — a bare "not found" from the VCS system specifically is `UNKNOWN`, not an
  automatic pass, since some hosting platforms return 404 for a private/inaccessible repository
  the same way they do for one that no longer exists.
- **TeamCity validation surfaced in the API — coordinated deploy required.** `TeamcityProjectResponse`
  gains `validations: List<ValidationResponse>` (`type` + `status` + optional `message` +
  optional `updatedAt`, e.g. `USES_OLD_JAVA_VERSION` / `WARNING`), and new admin endpoints
  `GET /rest/api/4/admin/teamcity-validations` and `.../summary` expose per-project findings
  (`@permissionEvaluator.canImport()`). Findings are populated by a new post-sync + on-demand
  validation job — see [Upgrade note](deployment/tc-validation-upgrade-note.md) for the required
  `teamcity.validation.*` configuration. **This service must be deployed together with the
  components-management-portal release that understands this contract** — do not roll it out
  independently.
- **`TeamcityProjectResponse.projectVersion` added (nullable).** Component detail
  (`GET /rest/api/4/components/{id}`) now exposes `teamcityProjects[].projectVersion` — the
  TeamCity `PROJECT_VERSION` release line a linked project belongs to, or null when it declares
  none. TeamCity sync now stores **one project per distinct `PROJECT_VERSION` line**, so a
  component may surface multiple `teamcityProjects` entries (ordered by version then id). Purely
  additive; clients that ignore the field are unaffected.
- **`ErrorResponse.errorCode` added (nullable) + uniqueness-violation wording.** Error bodies now
  carry a machine-readable `errorCode` alongside `errorMessage`: `OPTIMISTIC_LOCK` (stale `version`
  on PATCH — reload and re-apply), `UNIQUENESS_VIOLATION` (cross-component uniqueness: distribution
  GAV, jira projectKey+versionPrefix, docker image name, component rename to a taken name),
  `DATA_INTEGRITY` (DB constraint). Absent/null on other errors and on older servers; clients must
  tolerate unknown values. All uniqueness 409 messages now start with `uniqueness violation:`.
  Consumers should branch the 409 UX on `errorCode`, not on the message text. Additionally the
  distribution-GAV collision identity now includes `extension` and `classifier` — `g:a:zip` and
  `g:a:apk` on two components no longer 409 (this previously blocked ANY save of such components).
- **OpenAPI spec generation wired (TD-003).** The v4 surface is now published as a machine-readable
  `v4.json` (OpenAPI 3.0.1) and gated for drift. No behavioural API change — this baselines the
  contract. `info.version` is the constant `"4"`.
