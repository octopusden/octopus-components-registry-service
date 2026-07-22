# Upgrade note: TeamCity validation (`teamcity.validation.*`)

This note covers the operational rollout of the TC validation feature (SYS-064, SYS-075–092).
It is additive-only: no schema migration in this change touches existing `teamcity_project`/
`version_line`/`teamcity_validation` data, and the feature ships **inert** in this repository —
see the activation model below.

## Activation model

`TeamcityValidationProperties` (bound from `teamcity.validation.*`) has no `enabled` flag, and its
five template/step-id fields have **no Kotlin default value** — each one is a required
constructor parameter. That means:

- Every profile (this repo's `application.yml`, `service-config` per environment, and every test
  profile) **must explicitly declare all five keys**. If any key is omitted entirely from every
  active profile, Spring Boot's constructor binding fails at startup (`BindException` /
  `NullPointerException` while binding `teamcity.validation.*`) — this is real, built-in fail-fast
  behavior, not custom code.
- **Fully blank/empty** (`gradle-build-template-id: ""`, `maven-build-template-id: ""`,
  `release-family-template-ids: []`, `gradle-default-build-step-id: ""`,
  `maven-default-build-step-id: ""`) is the inert baseline shipped in this repo's
  `application.yml` — a deliberate, explicitly-declared "off" state, not an omission. Validation
  endpoints exist and run, but every result comes back with no findings, since
  `ConfigTemplateCatalog` has nothing to check against.
- **Fully set** — the real per-environment values (below), supplied via `service-config`.
  Validation runs for real after every sync and on demand.
- **Partially set** (e.g. the two template ids filled in but a default-build-step id left blank)
  is **not specially detected**. Nothing fails at startup in this case, because every key is still
  present (just some are blank) — Spring's required-parameter check only catches a fully missing
  key, not a blank one sitting next to real ones. A partial config like this can silently produce
  misleading "clean" results (e.g. a blank default-build-step id makes
  `OVERRIDES_DEFAULT_BUILD_STEP` report nothing instead of flagging real drift). There is currently
  no dedicated startup guard against this specific case — treat "set all five together, in the
  same `service-config` change" as an operational rule for now (see Rollout procedure below), and
  double-check via the `/summary` endpoint after any change to this block. A stronger structural
  guard (e.g. a startup validator) was considered and intentionally left out of this PR — it can
  be revisited later if partial-config incidents actually happen in practice.

Do not hardcode real template/step ids in this repository's `application.yml` — they are
environment-specific TeamCity topology, not product defaults.

## Per-environment values (service-config)

Set all five properties together in each environment's `service-config`
(`components-registry-service.yml`, mirroring how `teamcity.base-url`/`teamcity.sync.*` are
already externalized):

```yaml
teamcity:
  validation:
    gradle-build-template-id: <real Gradle build-template id from the live TC instance>
    maven-build-template-id: <real Maven build-template id from the live TC instance>
    release-family-template-ids:
      - <real release-family template id 1>
      - <real release-family template id 2>
      - <real release-family template id 3>
    gradle-default-build-step-id: <real default build-step id for the Gradle template>
    maven-default-build-step-id: <real default build-step id for the Maven template>
```

Every profile must declare all five keys explicitly (see Activation model above) — test profiles
supply their own fixture values (see `application-integration-test.yml`'s `teamcity.validation.*`
block, used by the fat-jar startup tests, and the `test-db-validate` profile used by the
`TeamcityValidationRepeatedRunIntegrationTest` family) so nothing needs a live TeamCity instance in
CI.

## Rollout procedure

1. Deploy this change with `teamcity.validation.*` explicitly blank in `service-config` (matching
   this repo's `application.yml` baseline). The application boots normally; validation runs but
   reports nothing.
2. Confirm the real template/step ids for the target TeamCity instance.
3. Set all five `teamcity.validation.*` properties together, in the same `service-config` change,
   and roll the deployment. Setting them one at a time (or leaving one blank) will NOT fail
   startup — see the "partially set" note above — so treat "all five together" as a manual
   checklist item during review of the `service-config` change, not something the app enforces.
4. Verify via `GET /rest/api/4/admin/teamcity-validations` (or the `/summary` endpoint) that
   findings are being produced for at least one known-misconfigured project.

## Rollback

Blank out all five properties in `service-config` (explicitly, not by omitting the block — see
Activation model) and redeploy — this returns the feature to its inert baseline without requiring
a code change or data migration.
