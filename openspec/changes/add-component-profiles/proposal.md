## Why

- The Portal's Create component start page offers a fixed list of profiles (regular external,
  regular internal, Solution, DMP Bundle) built into the Portal. Adding, renaming or reordering one
  needs a Portal release, and another installation cannot have a different list.
- The solution key rules exist only in the Portal (`portal.component.solution-key-patterns`), so
  the registry cannot check a key against the profile it was created with.
- Component templates will be offered on the same start page and carry their own field rules, so
  the registry has to own both the profile list and the rules.

## What Changes

**Profiles from configuration**
- New subtree `components-registry.component-profiles` in service-config: a map of profile id →
  profile, with `kind`, `title`, `description`, `order`, `classification.{external, explicit,
  solution}` and optional field `rules` (create-request path → `pattern` + `message`).
- Every entry is parsed strictly against a fixed list of keys and allowed values (stated in the
  spec): a missing required key, an unknown key, a value outside the allowed values, an invalid
  rule pattern, a rule without a message, or a rule on a path outside the list makes the entry
  invalid, naming the key.
- At least one valid `regular` profile is required, and every `regular` profile must be valid.
- `kind: template` entries are accepted as a kind but are not offered yet: each is reported as
  failed with "templates are not supported yet". Templates land in their own change.

**Startup and reload**
- CRS fails to start when the subtree cannot be read, has no `regular` profile, or has an invalid
  one, and reports why.
- `POST /rest/api/4/admin/reload-config` re-reads the profiles. A usable result replaces the
  profiles in use at once. One with no `regular` profile or an invalid one is not applied: CRS
  keeps the profiles in use and the reload answers 422 with the reasons.
- The reload response lists every entry with its status (`live` or `failed`) and its problems.

**Listing for the current user**
- New `GET /rest/api/4/component-profiles` (`ACCESS_COMPONENTS`): live profiles in `order`, ties by
  id, each with its kind, title, description, classification, field rules (path, pattern,
  message) and whether the current user may use it, with the reason when not.
- The rules returned are the same rules a create naming the profile is checked against, so the
  Portal's regular create can check them while typing and keep using today's create request.
- In this change a user may use every profile when they hold `CREATE_COMPONENTS`. The
  Delivery & Support restriction plugs into the same availability rule in its own change.

**Solution key rules as profile field rules**
- The solution key rules are configuration, not code: the Solution and DMP Bundle profiles carry
  a `name` rule requiring their word, and the two regular profiles carry a `name` rule that keeps
  `solution` and `dmp-bundle` out of the key (so `resolution-service` is rejected).
- There is no built-in naming convention: the rules apply only to a create that names a profile.

**Profile on today's create request**
- `ComponentCreateRequest` gains an optional `profile`; the endpoint and the rest of the request
  are unchanged. When given, CRS checks that it is a live `regular` profile, that the user may
  use it, that the request's classification matches it, and every field rule of the profile.
- When absent, the create is checked as today.

## Capabilities

### New Capabilities

- `component-profiles`: configured profiles — parsing, startup and reload, the listing for the
  current user, and the optional profile on create with its field rules.

### Modified Capabilities

None.

## Impact

- `components-registry-service-server`: profile parsing and catalog, `reloadConfig`, new
  controller, `ComponentCreateRequest.profile`, the create path in
  `ComponentManagementServiceImpl`. The update path is unchanged.
- **No behavior change for existing API clients:** a create without `profile`, a rename and a
  solution-flag change are checked exactly as today.
- **Startup now requires configuration:** CRS does not start without at least one valid
  `regular` profile. service-config must carry the profiles before this version is deployed.
- `POST /admin/reload-config` response gains the profile result; the existing `status` and
  `changedKeys` stay.
- v4 contract change → `docs/registry/api-changelog.md` entry and regenerated `v4.json`;
  `functional-spec.md` and ADR-016 (a third subtree under the same delivery and reload path).
- Portal: unaffected until it reads the profiles and sends `profile` (separate change); its own
  solution-key setting keeps working meanwhile.

## Out of scope

- **Templates** — parameters, rendering, dry run, create from a template and their load checks
  belong to the templates change; here a template entry only fails.
- **Delivery & Support restriction** — which roles count as D&S is not decided; the availability
  rule is the seam it will use.
- **Administrator read of all profiles and templates** — arrives with the templates change; until
  then the reload response is where an administrator sees the problems.
- **The other Portal-only create rules** (Jira key and full version format, VCS path, branch and
  tag, `ssh://` host, group-ID prefix, complete coordinate) — tracked as a separate change; only
  the solution key rules move here.
- **A global solution naming convention** — renames, solution-flag changes and creates without a
  profile are not checked against the key rules; enforcing them on every change is a separate
  change.
- **Recording the profile on the component** — the profile is a create-time choice; nothing reads
  it afterwards.
- **DSL import and field overrides** — the import is a migration path for existing data; field
  overrides carry no profile.

The global naming convention and the other Portal-only create rules are recorded as open
tech-debt items in `docs/registry/tech-debt/`, so the limitation stays visible after this change
ships.

## Rollout note

- Merge and deploy the service-config change with the four profiles first; a CRS version from this
  change does not start without them.
- Existing components are never re-checked against the rules, so no data clean-up is needed.
