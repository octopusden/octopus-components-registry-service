# ADR-016: Admin Configuration as Code (field-config + component-defaults)

## Status
Accepted — amends [ADR-011](011-field-configuration.md).

## Context

ADR-011 introduced two admin configuration blobs — `field-config` (per-field
`visibility` / `searchable` / `required` / `defaultValue`) and `component-defaults`
(default values for new components) — stored as JSON rows in `registry_config` and
**edited at runtime via admin `PUT` endpoints and the Portal**.

Operating that model surfaced three problems:

1. **No review / no provenance.** Admin edits hit the database directly. There is no
   PR review, no version history, and drift between installations is invisible
   ("who changed this in prod?").
2. **`component-defaults` anchored the Groovy stack.** Defaults were read from
   `Defaults.groovy` via `component-resolver-core` (ConfigSlurper). That keeps the
   legacy Groovy loader alive purely for defaults, blocking its eventual removal.
3. **Two natures, one editing model.** `field-config` is *application/UI policy*
   (which fields an installation exposes — including features it never uses, marked
   `visibility: hidden`); `component-defaults` is *domain policy*. Both are
   per-installation and change rarely — exactly the profile of code-as-config, not
   hot DB edits.

## Decision

Make both blobs **code-as-config**, sourced from **`service-config`** (Spring Cloud
Config), rendered **read-only** in the Portal, reloadable **without a redeploy**.

### Source of truth — service-config

Both subtrees live under the `components-registry` prefix and are bound by
`AdminConfigProperties` (a mutable `@ConfigurationProperties` bean, so Spring Cloud's
`ConfigurationPropertiesRebinder` refreshes it in place):

```yaml
# components-registry-service.yml  (base — permanent org policy)
components-registry:
  field-config:
    component:
      displayName: { visibility: editable, searchable: Main, required: false }
      groupId:     { visibility: editable, searchable: Main, required: true, defaultValue: com.example }
      clientCode:  { visibility: hidden }     # feature this org does not use
    build:
      javaVersion: { visibility: readonly, searchable: Extended }
      projectVersion:                       # display-rename override: policy keys may be absent
        label: Example Label
        description: Example tooltip text shown next to the field in the Portal.
  component-defaults:
    buildSystem: MAVEN
    build: { javaVersion: "17", requiredProject: true }
    jira:  { projectKey: PROJ, technical: false }
    labels: [core]
```

- `visibility ∈ {editable, readonly, hidden}`; `searchable ∈ {Main, Extended, None}`.
  Fields absent from `field-config` default to `editable` (unchanged
  `FieldConfigService` fallback).
- `defaultValue` for `component.groupId` replaces the old `FieldConfigSeeder`
  (which derived it from `supportedGroupIds`); it is now an explicit per-installation
  value in the base profile.
- `label` / `description` are free-text **display overrides**: `label` replaces the
  Portal's hardcoded field label everywhere in the component editor, `description`
  replaces the tooltip text from the Portal's `fieldDescriptions` registry. Values are
  trimmed; blank values are dropped from the cache blob. Both are optional and
  independent of the policy keys — an entry may carry only `label`/`description`
  (visibility then defaults to `editable` as usual). When absent, the Portal falls
  back to its hardcoded label and tooltip.
- **Dotted field keys need bracket notation.** A field key that itself contains a
  dot (the `distribution` section uses keys like `maven.groupPattern` — both
  consumers, CRS `FieldConfigService` and the Portal resolver, split the path on
  the FIRST dot only) must be quoted in brackets, or
  the Spring binder treats the dot as a path separator and silently drops the leaf
  (pinned by `AdminConfigPropertiesBindingTest`):

  ```yaml
  field-config:
    distribution:
      "[maven.groupPattern]": { label: Example Label }
  ```

### Per-environment (QA vs Prod)

`hidden` carries two meanings: **permanent org policy** ("we don't use this feature")
and **transient rollout toggle** ("enabled on QA, not yet on Prod"). Permanent policy
goes in the base `components-registry-service.yml`; transient deltas go in the
profile files (`-cloud-qa.yml` / `-cloud-prod.yml`) and override the base — Spring
Cloud Config merges base + profile automatically.

> **List-merge caveat.** Spring property merging **replaces** list values wholesale,
> it does not deep-merge. A profile delta for any list field
> (`component-defaults.labels`, `escrow.providedDependencies`,
> `escrow.additionalSources`) must **restate the full list**, or it will be truncated.

### DB cache — unchanged readers

`registry_config` is kept as a **cache**, not the source of truth. On startup and on
reload, `ConfigSyncService` serializes the bound beans into the exact legacy
`Map<String,Any?>` blob shape and upserts `registry_config['field-config']` and
`['component-defaults']`. All existing readers — `FieldConfigService`, the
`GET /rest/api/4/config/...` endpoints, and the Portal — read the cache unchanged.

Guarantees:
- **Validation** — invalid `visibility`/`searchable` aborts the sync
  (`ConfigValidationException`); a misconfiguration fails loudly rather than silently
  degrading enforcement.
- **No-clobber** — an *empty* bound subtree is never written, so a missing profile
  section cannot blank out known-good production policy (which would otherwise drop
  `FieldConfigService` to `editable` everywhere).
- **Transactional** — both keys are written in one transaction; a bad
  `component-defaults` cannot leave `field-config` half-updated.
- **Ordering** — the startup sync runs in
  `ComponentsRegistryServiceImpl.@PostConstruct` *before* any auto-migration, since
  `migrateDefaults()` consumes `component-defaults`.
- **no-db mode** — `ConfigSyncService` is `@ConditionalOnDatabaseEnabled`; in no-db
  mode there is no cache and no sync (injected as nullable into
  `ComponentsRegistryServiceImpl`).

### Reload without redeploy

`POST /rest/api/4/admin/reload-config` (auth: `canImport()`) calls
`ContextRefresher.refresh()`, which re-fetches the profile from the Config Server,
rebinds `AdminConfigProperties` (`EnvironmentChangeEvent`) and fires
`RefreshScopeRefreshedEvent` → `ConfigRefreshListener` re-syncs the cache
synchronously. The raw `/actuator/refresh` endpoint is **not** exposed; reload is
gated through this admin endpoint. The Portal "Reload" button calls it.

### Writes removed

The legacy `PUT /rest/api/4/admin/config/field-config` and
`.../component-defaults` now return **410 Gone** with a "managed as code" message
(not 404/405, so outdated clients get an actionable signal). The `GET` endpoints
remain. `POST /admin/migrate-defaults` and the migration-job DEFAULTS phase remain
but now source defaults from `service-config` (no Groovy).

### Groovy detachment (scope)

`ImportServiceImpl.migrateDefaults()` no longer reads `Defaults.groovy`; it delegates
to `ConfigSyncService`. Full removal of `component-resolver-core` / the migration
Groovy stack is a **separate follow-up** (gated on migration being complete across
all installations).

## Consequences

### Positive
- Config changes are PR-reviewed, versioned, and reproducible across installations.
- Native per-env support (QA/Prod) via Spring Cloud Config profiles.
- Reload without a pod restart.
- `component-defaults` no longer anchors the Groovy loader.
- Clean separation: `field-config` (system/UI) and `component-defaults` (domain) stay
  logically distinct (separate keys/beans) while sharing one delivery + reload path.

### Negative
- Admins lose in-UI editing; changes require a service-config PR + reload.
- The `registry_config` cache must be kept in sync with the bound config; the sync
  validation + no-clobber rules exist to make that safe.
- The base/profile split must respect the list-merge caveat above.

### Interaction with other ADRs
- **ADR-011** — supersedes its "edited at runtime via admin UI/PUT" decision; the
  schema and visibility semantics are retained.
- **ADR-005 (Audit Log)** — config changes are now audited in `service-config` git
  history rather than the `audit_log` table.
- **ADR-013 (Cutover)** — the `component-defaults` values must be reconciled with the
  current production blob (snapshot `GET /rest/api/4/config/component-defaults`)
  before cutover; a golden parity check guards the serialized key set.

## References
- `AdminConfigProperties`, `ConfigSyncService`, `ConfigRefreshListener`
- `ConfigControllerV4` (410 writers), `AdminControllerV4#reloadConfig`
- service-config: `components-registry-service{,-cloud-qa,-cloud-prod}.yml`
