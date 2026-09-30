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

- **`involves` / `involvesRoles` list filters added (SYS-101).** `GET /components?involves=<u>` returns
  components where any listed user is the owner OR a release manager OR a security champion;
  `involvesRoles` (any of `owner`, `releaseManager`, `securityChampion`; default all) narrows the
  roles, OR across them. Combines with the other filters via AND; one row per component; an unknown
  role is `400`. Backs the Portal's "Mine" filter.
- **`GET /components/as-code/search` added (SYS-100).** Global text search over every component's
  FULL as-code view (`GET /components/{id}/as-code`), the replacement for grepping the Groovy DSL
  files. `ACCESS_COMPONENTS`. Params: `q` (required, 2–200 characters after trimming;
  case-insensitive substring), `regex` (default `false`; `true` matches `q` as a case-insensitive
  regular expression in RE2 syntax — no lookarounds or backreferences), `archived` (omitted = both), `limit` (components returned, 1–1000, default
  100), `maxMatchesPerComponent` (1–1000, default 20). Returns `AsCodeSearchResponse`
  `{query, regex, totalComponents, truncated, results}`; each `AsCodeSearchHit` is
  `{id, componentKey, archived, matchCount, matches}` sorted by key (`id` = component UUID), and each `AsCodeSearchLine` is
  `{line, text, path, ranges}` — the 1-based line number in the as-code view, the line without its
  indentation, the enclosing block headers outermost first, and the matched spans of `text`
  (`AsCodeMatchRange {start, end}`, end exclusive) as found by the server's matcher, for exact
  highlighting of regex hits too. `400` for a bad `q`/`limit`, an
  invalid or non-RE2 regex, a regex that compiles to an oversized program, or a regex that exceeds the per-request evaluation time budget. The RMS section of the as-code
  view is not searched. Results may lag an edit that bypasses the component tables and the audit log
  (e.g. TeamCity version-line sync) by up to 5 minutes.
- **`testComponent` flag (SYS-099).** `ComponentSummaryResponse` and `ComponentDetailResponse` gain a
  required `testComponent: boolean`; `ComponentCreateRequest` (default `false`) and
  `ComponentUpdateRequest` (`null` = unchanged) accept it; changing it needs only the plain
  component edit permission. `GET /components` gains `?testComponent=true|false`;
  without it test components are still listed. `testComponent = true` needs a component key
  matching one of the configured name patterns (default `^test-`, `^cvelab-`; bound at startup, a
  change needs a restart): a create or PATCH that turns it on, or a rename of a flagged component,
  with a non-matching key returns `400` (`testComponent: …` / `name: …`, naming the key and the
  patterns); echoing an unchanged value and unflagging are always allowed.
  New `GET /components/meta/test-component-name-patterns` (`ACCESS_COMPONENTS`) returns the active
  patterns as `string[]`, e.g. `["^test-","^cvelab-"]`. Create and PATCH also return `400` when a
  non-test component would reference a test component as its parent or doc component, or when a
  component that non-test components reference that way is flagged or renamed; the message names
  both components and starts with the field that changed (`parentComponentName: …`, `docs: …`,
  `testComponent: …` or `name: …`). On PATCH these checks are change-based: an unchanged echo of
  the General slice is accepted. A write that loses a row lock to a concurrent one (a deadlock)
  returns `409` with the new `errorCode` `LOCK_CONFLICT`; nothing was changed, retry it. `warnings` carries an entry
  when the component has the `test-component` label but the flag is false (the label is kept for
  its readers; the flag is the source of truth). `GET /health/statistics` and
  `GET /admin/teamcity-validations` (and `/summary`) no longer count test components. `V11__` flags
  every component labelled `test-component` and keeps the label; importing from the DSL applies the
  same label rule. Health and TeamCity-validation totals exclude test components while the bare list
  includes them, so a drill-down from a total must pass `testComponent=false`. The v1–v3 `Component` payloads (list, detail, detailed component, v3 list,
  find-by-artifact) always carry `testComponent` — the stored flag for a DB-sourced component, the
  `test-component` label for a Git-sourced one (and in no-db mode); released clients ignore the new
  key (their DTOs ignore unknown properties). The flag is not part of the legacy Groovy DSL or the as-code view.

- **Component templates.** A `kind: template` entry under `components-registry.component-profiles`
  is now read: fixed classification, parameters, fields built from them, overridable paths and
  optional field rules. A template is checked on load and reload; a broken one is `failed`, is not
  offered, and never blocks a load. A template counts as a configured entry: a subtree with
  templates but no `regular` profile does not start CRS, and a reload of it answers `422`.
  - **`GET /rest/api/4/component-profiles`** lists live templates next to the regular profiles, in
    one `order`, then id: `kind` `template` and a new `version` (`null` for a regular profile). A
    client that assumed every entry is `regular` must branch on `kind`. A template's
    `classification.explicit` is never `ask`.
  - **`POST /rest/api/4/admin/reload-config`**: a valid template entry is now `live` in
    `componentProfiles.entries`; only a broken one is `failed`, with its own problems instead of
    "templates are not supported yet".
  - **`GET /rest/api/4/component-templates/{id}` added** (`ACCESS_COMPONENTS`, database mode): a live
    template's `id`, `version`, `title`, `description`, `classification`, `overridable` and
    `parameters` in configured order — `name`, `label`, `hint`, `type` (`text`, `select`,
    `crs-list`, `person`), `required`, `multiple`, `default`, and the type's settings (`pattern`,
    `message`, `maxLength`; `options`, `maxSelection`; `list` with its current `values`). A `person`
    default `current-user` comes back as the caller. `404` for a failed or unknown template.
  - **`POST /rest/api/4/component-templates/{id}/components` added** (`ACCESS_COMPONENTS`, and a
    template the caller may use: `CREATE_COMPONENTS`; database mode). Body: `parameters` and
    `overrides` (maps to lists of strings), `jiraTaskKey` and `changeComment` as on any create. A
    parameter left out, empty or blank takes its default; a repeated value counts once.
    - When several request checks fail, the first answers: `jiraTaskKey` `400`, template `404`,
      use `403`, override `403`, override path `400`.
    - `dryRun` absent or `true` → `200` with `valid`, `parameterProblems` (`parameter`, `check`
      `P1`–`P9`, `message`), the rendered `component`, `sources` (path → parameters), `overridden`
      (the paths an override set) and `problems`
      (`fields`, `parameters`, `templateProblem`, `message`). Nothing is created.
    - `dryRun=false` → `201` with the component when nothing failed; otherwise `422` with the dry-run
      body, and nothing is created.
    - At most one problem comes from today's create rules, worded as the create words it; every
      parameter and rule problem is reported.
    - An override is checked as a parameter filling the field would be: a label or CRS value in
      its list, a login an active employee, one value for a field that is not a list; a failure is
      a problem on the field.
    - `400` for a malformed `jiraTaskKey` or an override on a path the template does not list;
      `403` when the caller may not use the template or override its fields; `404` for a failed,
      removed or unknown template.
  - **`GET /rest/api/4/admin/component-profiles` added** (`IMPORT_DATA`, database mode): every
    configured entry in use, live or failed, with `status`, `problems`, the parsed `profile` or
    `template`, and its `configuration` as YAML; `configVersion`, the config server's version of the entries in use, and
    `lastLoad`, the outcome of the last load or reload in the `componentProfiles` shape.
  - **No change for a regular create:** a create without `profile`, or naming a regular profile,
    is validated as before; a template id given as `profile` is still an unknown profile.

- **Create-component profiles from configuration.** The start-page profiles are configured in
  service-config under `components-registry.component-profiles` and checked when CRS starts and on
  reload. Without the subtree, or with it written empty, CRS starts with no profiles; a subtree
  written as a plain value or a list, or holding entries without a valid `regular` profile, stops
  CRS.
  - **`GET /rest/api/4/component-profiles` added** (`ACCESS_COMPONENTS`): `{ profiles: [...] }`, the
    live profiles in `order`, then id. Each carries `id`, `kind` (`regular`), `title`,
    `description`, `classification` (`external`, `explicit` as `true` / `false` / `ask`,
    `solution`), `rules` (`path`, `pattern`, `message`; `[]` when none; `pattern` is written for
    both Java and JavaScript regular expressions — a client that cannot compile one skips its own
    check), `usable` and, when not usable, `unusableReason`. A profile is usable for a caller with `CREATE_COMPONENTS`.
  - **`ComponentCreateRequest.profile` added** (optional; blank = absent). When given, the create
    fails with `400` and `errorMessage` `profile: …` when the profile is not configured, or when the
    classification the create stores (hidden fields dropped; absent = `false`) differs from the
    profile's, naming the flag (`explicit: ask` takes either value); with `403` and the reason when
    the caller may not use it; and with `400` `<path>: <rule message>` when a field rule fails. A
    rule matches the whole stored value (trimmed; absent = empty); a `[0]` path checks the first
    list entry only.
  - **`POST /rest/api/4/admin/reload-config`** responses gain `componentProfiles`: `status`
    (`applied` / `failed`), `problems` and `entries` (`id`, `kind`, `status` `live` / `failed`,
    `problems`). Profiles that are not usable are kept as they were and the reload answers `422`
    with `error: component-profiles`; an invalid `field-config` still answers `422`
    `config-validation`, now also carrying `componentProfiles`; any other refresh failure answers
    `500` with `error: config-refresh`, the message and `componentProfiles` — this 500 no longer
    carries the generic `ErrorResponse` (`errorMessage`). The responses are published as
    `ReloadConfigResponse` (200) and `ReloadConfigFailureResponse` (`error`, `message`,
    `componentProfiles`; 422 and 500). `status` and `changedKeys` are unchanged. Overlapping
    reloads run one after the other.
  - **No behavior change for existing clients:** a create without `profile`, a rename and a
    solution-flag change are validated exactly as before.

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
    `COMPILE_PAUSED`, `MANUAL_EDIT`, `IN_SYNC`, `TC_ERROR`, `OUTSIDE_SCOPE`), current and derived
    Checkout Directory / Source Path per VCS entry, current and derived Build Working Directory,
    the source TeamCity build type ids, and human-readable notes. `INVALID`: the derived values
    parse fine but fail the SAME CRS validation a v4 write runs (Source Path shape, reserved/
    duplicate Checkout Directory names, at most one root at the checkout root, name uniqueness,
    Build Working Directory rules); `notes` carries the validation message. `UNEXPRESSIBLE` is
    narrower now: only a checkout-rule or `WORK_DIR` SHAPE that can't be parsed at all (a remap,
    several rules on one entry, `%VAR%`) — a value that parses but fails CRS validation is
    `INVALID` instead. A repository attached twice within ONE build type with two different
    RESOLVABLE rules is `CONFLICT` (same as two build types disagreeing), not `UNEXPRESSIBLE`.
    `OUTSIDE_SCOPE`: a marker (per-range `vcs.settings`) row, or any row of an archived component —
    never derived, never selectable for Sync; an archived component's rows are now reported this
    way instead of omitted. Repository matching is by the full canonical VCS URL, host included
    (previously host-agnostic, a false-positive-match risk across TeamCity hosts).
  - `POST /sync` (`IMPORT_DATA`, body `{"diffId": "...", "componentIds": [...]}`) / `GET /sync/job`
    — applies the named Diff's `RESOLVED` rows for the given components, re-deriving first and
    skipping any row that changed since the Diff snapshot. **`diffId` is now required**: if it does
    not match the latest COMPLETED Diff, the whole request is refused with `409` and nothing is
    written (Diff keeps no history, so a stale `diffId` means the result the caller saw has been
    replaced — run Diff again; a `diffId` matching a still-RUNNING Diff is refused the same way).
    Writes go through the same v4 write path a human PATCH uses, tagging `changeComment` as
    `"sync from TeamCity (job <jobId>)"` — the Sync run's own id, so its audit rows can be selected
    for rollback. A row that is `OUTSIDE_SCOPE` (a marker row, or an archived component's row) is
    never written even if somehow selected; its outcome is `"skipped: outside scope"`. A value the
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
