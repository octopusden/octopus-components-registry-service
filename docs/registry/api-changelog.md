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
