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
  Binary note for Kotlin consumers of the published v2 DTOs: `VersionControlSystemRootDTO` and
  `VCSSettingsDTO` keep their previous JVM constructors (six and two parameters), but their `copy`
  methods and default-argument constructors change signature, so Kotlin code calling them must be
  recompiled against the new version; Java and Groovy callers of the previous constructors are
  unaffected.

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
