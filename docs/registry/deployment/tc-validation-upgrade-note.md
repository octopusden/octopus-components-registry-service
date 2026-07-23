# Upgrade note: TeamCity validation (`teamcity.validation.*`)

This note covers the operational rollout of the TC validation feature (SYS-064, SYS-075–092).
It is additive-only: no schema migration in this change touches existing `teamcity_project`/
`version_line`/`teamcity_validation` data.

## Configuration is mandatory

`TeamcityValidationProperties` (bound from `teamcity.validation.*`) has no `enabled` flag and no
"off" state. All five template/step-id fields are **required and must be non-blank**, enforced by
Bean Validation on the properties class (`@Validated` + `@NotBlank` on the four string fields,
`@NotEmpty` on `release-family-template-ids`). If any is blank or empty, **the application fails to
start**.

This is deliberate. The validation suite is meaningless without a real `ConfigTemplateCatalog`:
with blank ids, `isBuildTemplate(...)` never matches any real template, so `ATTACHED_TO_BUILD_TEMPLATE`
would flag *every* project as "not attached to a build template" and the other checks would run
against a catalog that recognizes none of our templates — i.e. invalid findings. Rather than ship a
fake "inert" state that silently emits garbage, the app requires the real values before it will
boot.

Note: declaring the fields as non-null Kotlin `String`s does NOT enforce this on its own — a blank
YAML value like `gradle-build-template-id:` binds to `""` (Spring converts a null YAML scalar to an
empty string), which is non-null and would bind fine. The `@NotBlank`/`@NotEmpty` constraints are
what actually reject blanks at startup. `release-family-template-ids` is additionally
`Set<@NotBlank String>`, so a blank/whitespace-only element (`[""]`) is rejected too — otherwise it
would match no real template and push release configs into `notAttachedToBuildTemplate` as false
WARNINGs.

## The bundled baseline is intentionally blank (fail-fast)

The repo `application.yml` ships **all five values blank/empty** — there are no working defaults or
placeholders bundled:

```yaml
teamcity:
  validation:
    gradle-build-template-id:
    maven-build-template-id:
    release-family-template-ids: []
    gradle-default-build-step-id:
    maven-default-build-step-id:
```

Because the config is mandatory (above), this baseline **fails startup by design** in any context
that doesn't override it. The real values are supplied per environment; there is nowhere in this
repository that carries live TeamCity ids.

## Per-environment values (service-config) — required BEFORE deployment

Every environment must set all five values in its `service-config`
(`components-registry-service.yml`, mirroring how `teamcity.base-url` / `teamcity.sync.*` are
handled) **before the pod is rolled** — the service will not start otherwise:

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

Confirm the real ids against the live TeamCity instance per environment (decision D2).

## Test / CI setup

Tests do NOT rely on the (blank) `application.yml` baseline — each context that boots the full app
supplies its own non-blank fixture values:
- server unit/integration tests: `components-registry-service-server/src/test/resources/application-common.yml`
  (every server `@SpringBootTest` activates the `common` profile; `test-db-validate` and the other
  secondary profiles inherit these fixtures — they carry no `teamcity.validation` block of their own).
- fat-jar startup tests: `application-integration-test.yml`.
- client / light-client modules: their own `application-test.yml` / `application-common.yml`.
- the `ocCreate` automation pod: `components-registry-automation/data/components-registry-service.yaml`.

These are throwaway fixture ids, not real TeamCity topology, so nothing needs a live TeamCity
instance in CI.

## Rollout procedure

1. Confirm the real template/step ids for the target TeamCity instance (decision D2).
2. Set all five `teamcity.validation.*` values in that environment's `service-config` **first**,
   then roll the deployment. If any is blank/empty (or a release-family element is blank) the pod
   fails to start — the `@NotBlank`/`@NotEmpty` violation in the startup log names the offending
   field — so a misconfiguration is caught at boot, never silently producing invalid findings.
3. Verify via `GET /rest/api/4/admin/teamcity-validations` (or the `/summary` endpoint) that
   findings are being produced for at least one known-misconfigured project.

## Rollback

There is no config-level "off" switch — the feature is always configured and always runs. To stop
producing findings, revert the deployment (remove the feature) rather than blanking the config,
which would fail startup.
