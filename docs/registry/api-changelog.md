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

- **Component templates.** A `kind: template` entry under `components-registry.component-profiles`
  is now read: fixed classification, parameters, fields built from them, overridable paths and
  optional field rules. A template is checked on load and reload; a broken one is `failed`, is not
  offered, and never blocks a load.
  - **`GET /rest/api/4/component-profiles`** lists live templates next to the regular profiles, in
    one `order`, then id: `kind` `template` and a new `version` (absent for a regular profile). A
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
    `overrides` (maps to lists of strings), `jiraTaskKey` and `changeComment` as on any create.
    - `dryRun` absent or `true` → `200` with `valid`, `parameterProblems` (`parameter`, `check`
      `P1`–`P9`, `message`), the rendered `component`, `sources` (path → parameters), `overridden`
      (the paths an override set) and `problems`
      (`fields`, `parameters`, `templateProblem`, `message`). Nothing is created.
    - `dryRun=false` → `201` with the component when nothing failed; otherwise `422` with the dry-run
      body, and nothing is created.
    - At most one problem comes from today's create rules, worded as the create words it; every
      parameter and rule problem is reported.
    - `400` for a malformed `jiraTaskKey` or an override on a path the template does not list;
      `403` when the caller may not use the template or override its fields; `404` for a failed,
      removed or unknown template.
  - **`GET /rest/api/4/admin/component-profiles` added** (`IMPORT_DATA`, database mode): every
    configured entry in use, live or failed, with `status`, `problems`, the parsed `profile` or
    `template`, and its `configuration` as YAML; `configVersion` from the config server, and
    `lastLoad`, the outcome of the last load or reload in the `componentProfiles` shape.
  - **No change for a regular create:** a create without `profile`, or naming a regular profile,
    is validated as before; a template id given as `profile` is still an unknown profile.

- **Create-component profiles from configuration.** The start-page profiles are configured in
  service-config under `components-registry.component-profiles` and checked when CRS starts and on
  reload; CRS does not start without at least one valid `regular` profile.
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
    `500` with `error: config-refresh`, the message and `componentProfiles`. `status` and
    `changedKeys` are unchanged. Overlapping reloads run one after the other.
  - **No behavior change for existing clients:** a create without `profile`, a rename and a
    solution-flag change are validated exactly as before.

- **`GET /meta/release-managers` and `GET /meta/security-champions` added.** Two new
  `ACCESS_COMPONENTS` endpoints return the sorted, distinct release-manager and security-champion
  usernames currently assigned to at least one component the v4 list shows (blank values excluded), as
  `List<String>` like `/meta/owners`. They list the values the existing `?releaseManager=` and
  `?securityChampion=` filters can match; each list holds only its own role.
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
