## Context

- ADR-016 delivers `field-config` and `component-defaults` from service-config:
  `AdminConfigProperties` is a mutable `@ConfigurationProperties` bean that Spring Cloud rebinds
  in place on `ContextRefresher.refresh()`; `ConfigRefreshListener` (DB mode only) then re-syncs
  the `registry_config` cache.
- `POST /rest/api/4/admin/reload-config` (`AdminControllerV4.reloadConfig`, `canImport()`) calls
  `refresh()` and returns `{status, changedKeys}`; a `ConfigValidationException` from the sync
  maps to 422.
- `ComponentManagementServiceImpl.createComponent` validates the key (`validateComponentKey`),
  uniqueness and the malformed-field rules (`validateMalformedFieldRules`) before the flush; the
  profile check runs in the same place.
- The create endpoint already requires `ACCESS_COMPONENTS` and `CREATE_COMPONENTS`.
- The Portal holds the profile list and the solution key patterns itself; the registry has
  neither.

## Example

The service-config subtree this change reads (the four profiles from the Portal today):

```yaml
components-registry:
  component-profiles:
    regular-external:
      kind: regular
      title: Regular external component
      description: An ordinary component delivered to the client.
      order: 10
      classification: { external: true, explicit: ask }
      rules:
        name: { pattern: "^(?!.*(solution|dmp-bundle)).*$", message: "A regular component's key cannot contain solution or dmp-bundle. Choose the Solution or DMP Bundle profile." }
    regular-internal:
      kind: regular
      title: Regular internal component
      description: An ordinary component for internal use only.
      order: 20
      classification: { external: false, explicit: ask }
      rules:
        name: { pattern: "^(?!.*(solution|dmp-bundle)).*$", message: "A regular component's key cannot contain solution or dmp-bundle. Choose the Solution or DMP Bundle profile." }
    solution:
      kind: regular
      title: Solution
      description: A top-level component that groups and ships other components together.
      order: 30
      classification: { solution: true, external: true, explicit: true }
      rules:
        name: { pattern: "^[a-z][a-z0-9-]*-solution(-[a-z0-9-]+)?$", message: "A solution key contains -solution, e.g. payments-solution." }
    dmp-bundle:
      kind: regular
      title: DMP Bundle
      description: A bundle component, also a solution, shipped as one distribution.
      order: 40
      classification: { solution: true, external: true, explicit: true }
      rules:
        name: { pattern: "^[a-z][a-z0-9_-]*dmp-bundle[a-z0-9-]*$", message: "A DMP bundle key contains dmp-bundle, e.g. payments-dmp-bundle." }
```

Creates against it:

| Request | Result |
|---|---|
| `name: payments`, `solution: true`, no `profile` | Accepted — no profile, checked as today |
| `name: resolution-service`, `profile: regular-internal`, `distributionExternal: false` | 400 `name: A regular component's key cannot contain…` — the regular profile's rule |
| `name: payments-solution`, `profile: solution`, solution/external/explicit `true` | Accepted |
| `name: payments-dmp-bundle`, `profile: solution`, solution/external/explicit `true` | 400 `name: A solution key contains -solution…` — the Solution rule |
| `name: payments-dmp-bundle`, `profile: dmp-bundle`, same classification | Accepted |
| `name: tools`, `profile: solution`, `solution: false` | 400 `profile:` — classification differs |
| `name: tools`, `profile: regular-external`, `distributionExternal: true`, `distributionExplicit: false` | Accepted — `explicit: ask` takes either |

## Goals / Non-Goals

**Goals:**
- Profiles are data in service-config, reloadable without a restart; the profiles are never
  half-applied.
- The solution key rules are configuration: profile field rules, checked on a create that names
  the profile.
- The Portal can draw the start page and run the same checks from one call.

**Non-Goals:**
- Templates, the D&S restriction, the administrator read API — later changes reuse the catalog,
  availability rule and path reader built here.
- Re-checking existing components, or checking renames, solution-flag changes and creates without
  a profile — those keep today's behavior.

## Decisions

### 1. Read the flattened keys from the property sources, not through `Binder`

- `ComponentProfilesSource` walks the environment's `EnumerablePropertySource`s and collects every
  key under `components-registry.component-profiles.`, the highest-precedence value winning per
  key — the same merge Spring applies. Keys are taken exactly as written.
- Each value is read from the first source holding that exact name, not through
  `Environment.getProperty`: Boot attaches a relaxed-lookup source that treats `regular-internal` and
  `regularinternal` as one name, so two distinct ids would receive the same values. Placeholders in
  a value are resolved against the environment. (changed on review)
- Why not `Binder` or a typed `@ConfigurationProperties` bean:
  - A typed bean ignores unknown keys and stops at the first type error; the requirement is to
    name every unknown key and every bad value.
  - Relaxed map binding strips any character other than letters, digits, `-` and `.` from an
    unbracketed map key, so `regular_external` would load as `regularexternal`; and a dotted key
    splits into nested maps (pinned for `field-config` by `AdminConfigPropertiesBindingTest`).
    Neither can be detected after binding.
- Each key splits as `<id>.<rest>`: the id is the first segment; under `rules.` the path is
  everything between `rules.` and the last segment (`pattern` or `message`), so
  `rules.artifactIds[0].groupPattern.pattern` yields the path `artifactIds[0].groupPattern`. No
  bracket notation is needed in YAML.
- `ComponentProfileParser` turns the flat key → value map into profiles plus a problem list. It is
  pure and unit-tested without Spring. Values may arrive as strings, numbers or booleans,
  depending on the source; the source hands each to the parser as a string, placeholders
  resolved, so the parser reads strings only.
- A non-enumerable property source cannot be walked and is not read; profiles come from
  service-config YAML, which is enumerable.

### 2. A validated snapshot, swapped whole

- `ComponentProfileCatalog` holds the live profiles, an immutable list, in an `AtomicReference`;
  `profiles()` hands a reader that list. Built as a bean by `config/ComponentProfilesConfig`, not
  a `@ConfigurationProperties` bean (Decision 1).
- A load either replaces the whole snapshot or leaves it untouched: no `regular` profile or an
  invalid one keeps every profile in use, including ones whose change was valid. A failed
  template entry does not stop a load.
- Spring refreshes the environment before anyone can validate it, so the catalog never reads the
  environment on a request — only on load.
- Readers (listing, create) take the snapshot once per call, so a reload mid-request cannot mix
  two configurations.
- The listing and the create check read the same snapshot, so the field rules the Portal gets are
  exactly the rules a create with that profile is checked against.

### 3. Startup fails loudly

- The catalog loads in its initializer. Unreadable subtree, no `regular` profile or an invalid
  one → `ComponentProfilesException` with every problem → the context does not start.
- Works the same in no-db mode; the catalog does not touch the database. `AdminControllerV4` is
  `@ConditionalOnDatabaseEnabled`, so no-db mode has no reload endpoint: its profiles change only
  with a restart, like the other admin configuration. (found during implementation)
- A configuration that cannot be read (an unresolvable placeholder, say) is a configuration-level
  problem: at startup it fails with `ComponentProfilesException` like any unusable load; on reload
  it keeps the profiles in use.
- The bundled `application.yml` carries no profiles — they are installation data, and a bundled
  map could not be shrunk by service-config (Spring merges maps by key). Every test and dev
  profile that starts the server gets a minimal profile set instead.

### 4. Reload result

- `reloadConfig` calls `AdminConfigReloader.reload()`: `contextRefresher.refresh()`, then
  `catalog.reload()`, and returns that outcome as `componentProfiles`: `status` (`applied` |
  `failed`), `problems` (configuration-level) and `entries` (id, kind, `live` | `failed`, problems).
- The refresh and the profile load are one critical section (`AdminConfigReloader` is
  synchronized). The profile source reads the environment a refresh replaces, so a second reload
  refreshing between the first's refresh and its load could hand that load a mix of two revisions.
  `ContextRefresher.refresh()` is synchronized on its own, but releases its lock before the profile
  load. (changed on review)
- No refresh listener: the outcome belongs to one request, so passing it through shared state
  would let two concurrent reloads read each other's result. The admin endpoint is the only
  refresh path (`/actuator/refresh` is not exposed).
- `catalog.reload()` runs whatever the refresh's outcome (the refresh is wrapped in `runCatching`),
  so the profiles are reloaded even when `ConfigRefreshListener` throws
  `ConfigValidationException` for `field-config`; that 422 then also carries `componentProfiles`. Any other refresh failure — a `field-config` value the rebinder
  cannot bind, say — answers 500 `config-refresh` with the message and `componentProfiles`, so the
  administrator sees whether the profiles changed. (added on review)
- A failed profile load answers 422 with `error: component-profiles` and the same body, matching
  the existing `config-validation` 422.
- Every reload response is typed and published in `v4.json`: `ReloadConfigResponse` for 200,
  `ReloadConfigFailureResponse` (`error`, `message`, `componentProfiles`) for 422 and 500. The 500
  replaces the generic `ErrorResponse`, whose required `errorMessage` the body does not carry.
  (changed on review)
- A template entry is `failed` with "templates are not supported yet" and never blocks a load.

### 5. Validation rules per entry

- The spec's tables are the reference; the parser implements them as-is.
- Id: lowercase letters, digits and `-`.
- `kind`: `regular` or `template`; a template entry fails until the templates change.
- Required: `kind`, `title`, `description`, `order` (whole number), `classification.external`
  (`true`/`false`), `classification.explicit` (`true`/`false`/`ask`). Optional:
  `classification.solution` (default `false`), `rules`.
- `solution: true` requires `external: true` and `explicit: true` (`ask` is not enough).
- `rules`: each key is one of the free-text create-request paths listed in the spec; each
  value has exactly `pattern` (compiles) and `message` (non-blank).
- Any other key → invalid, naming it. Every problem is collected, not just the first.

### 6. Availability is one function

- `ProfileAvailability.evaluate(profile) → Availability(usable, reason)`, a `fun interface`; this
  change's implementation is `PermissionProfileAvailability`, built in `ComponentProfilesConfig`
  over `PermissionEvaluator.hasPermission`. The D&S change replaces the implementation, not the
  callers.
- In this change: usable when the user holds `CREATE_COMPONENTS`; reason otherwise "You do not
  have permission to create components".
- The listing and the create check both call it, so the D&S change edits one place.
- The create endpoint already requires `CREATE_COMPONENTS`, so the 403 for an unusable profile is
  not reachable in this change; it becomes reachable with the D&S rule, and is tested now with a
  stubbed availability.

### 7. Profile on create

- Runs once the entity and base row are built — after the key, uniqueness and value checks, before
  the person-field and malformed-field checks. Order: unknown or non-`regular` id → 400
  `profile: unknown profile '<id>'`; not usable → 403 with the reason; classification mismatch →
  400 `profile: …` naming the differing flag; then each rule.
- Classification match: `solution`, `external` and `explicit` against the values the create will
  store — after `stripIfHidden` drops a flag hidden in the field configuration — not the raw
  request. `explicit: ask` takes either value. An absent or stripped flag counts as `false`, so
  a Solution profile with `component.solution` hidden rejects the create instead of storing a
  non-solution.
- A rule failure is 400 prefixed with the rule's path, carrying the configured message.
- A blank `profile` is the same as an absent one. The unusable 403 is a `ResponseStatusException`,
  as the create's other 403s.

### 8. Reading a create-request path

- `CreateRequestPaths.PATHS` is the list of rule paths in the spec; it validates rule keys on load.
- On create, rules and the classification read `EntityCreatedComponent`: the entity and base
  configuration row `createComponent` has built, just before the flush. So they see exactly what is
  stored — `name` trimmed, hidden fields stripped, blanks cleared — without repeating the create's
  normalization. Its reader map is keyed by exactly `CreateRequestPaths.PATHS` (pinned by a test).
  (changed during implementation: reading the request would have duplicated every strip and trim)
- `CreateRequestPaths.read(request, path)` stays for the templates change, which renders a request.
- An absent value is matched as `""`, so the pattern decides whether a field may be empty.
- A path with `[0]` reads only the first entry of that list; later entries are not checked. Rule
  paths name the first entry because that is the one a profile or template sets.
- The templates change reuses it to name the parameters behind a failing field.

### 9. Code placement follows the server's layers

No feature package; each class sits in the layer the server already uses for its kind.

- `model/` — `ComponentProfile`, `ProfileLoad`: internal data. Not DTOs: they carry the compiled
  rule regex and `order`, and the API shape (`dto/v4/ComponentProfileResponse`,
  `ComponentProfilesReloadResponse`) is mapped from them so it can change independently.
- `util/` — `ComponentProfileParser`, `CreateRequestPaths`: pure functions, no Spring. Inside the
  PIT mutation scope. They depend on `model/`, never the reverse: the entry kinds (`regular`,
  `template`) are `ComponentProfile` constants, so the move adds no `model` ↔ `util` package cycle
  (TD-016).
- `service/` — `ProfileAvailability`, the interface, beside the server's other service interfaces.
- `service/impl/` — `ComponentProfileCatalog`, `PermissionProfileAvailability`, `ProfileCreateCheck`,
  `CreatedComponent`: beside the other create checks (`PersonFieldValidator`,
  `DistributionCoordinateGuard`).
- `config/` — `ComponentProfilesSource` (reads the Spring `Environment`), `ComponentProfilesConfig`
  (the beans), `AdminConfigReloader`.

(changed on review: the classes first lived in one `profile/` package)

## Out of Scope

- See proposal. Technically: no new table, no migration, no change to `registry_config`.

## Risks / Trade-offs

- **A reload is not atomic across subtrees.** `field-config` and `component-defaults` are already
  rebound when the profiles fail; only the profiles keep their previous snapshot. Accepted: the
  existing subtrees already behave this way, and the response says which part failed. TD-026.
- **Startup depends on service-config.** A missing subtree stops CRS. Accepted: the requirement
  prefers a loud failure to an empty start page; the rollout note orders the deploys.
- **The key rules hold only for creates that name a profile.** An API create without `profile`, a
  rename or a solution-flag change can still produce a key that breaks them. Accepted: it keeps
  today's behavior for every existing client; enforcing them everywhere is a separate change.
  TD-024. The other Portal-only create rules stay in the Portal: TD-025.
- **Two regex engines read the same pattern.** CRS checks with Java, the Portal's fast check with
  JavaScript; a Java-only construct (`\p{Lu}`, possessive quantifiers) loads in CRS but breaks the
  Portal's check. Accepted: patterns are written in syntax both accept (spec and ADR-016 say so),
  and the Portal skips a pattern it cannot compile, leaving the create's answer to decide. Today's
  four patterns are valid in both. (raised on review)
- **Rule patterns are case-sensitive.** The regular profiles' rule rejects `solution` but not
  `Solution`. Accepted: component keys are lower-case by `validateComponentKey`.
