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
what actually reject blanks at startup.

## Per-environment values (service-config)

The repo `application.yml` carries the current OpenWay TeamCity defaults so local/dev/test contexts
boot. Each environment overrides them in its `service-config` (`components-registry-service.yml`,
mirroring how `teamcity.base-url` / `teamcity.sync.*` are handled):

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

Confirm the real ids against the live TeamCity instance per environment (decision D2) — in
particular the default build-step ids (`GRADLE_ID` / `MAVEN_ID` in `application.yml` are
placeholders that need real values). Test profiles supply their own fixture values (see
`application-integration-test.yml`'s `teamcity.validation.*` block, used by the fat-jar startup
tests, and the `test-db-validate` profile — which inherits the `application.yml` defaults) so
nothing needs a live TeamCity instance in CI.

## Rollout procedure

1. Confirm the real template/step ids for the target TeamCity instance (decision D2).
2. Set all five `teamcity.validation.*` properties in that environment's `service-config` and roll
   the deployment. If any is blank/empty the pod fails to start (`@NotBlank`/`@NotEmpty` violation
   in the startup log names the offending field) — a misconfiguration is caught at boot, never
   silently producing invalid findings.
3. Verify via `GET /rest/api/4/admin/teamcity-validations` (or the `/summary` endpoint) that
   findings are being produced for at least one known-misconfigured project.

## Rollback

There is no config-level "off" switch — the feature is always configured and always runs. To stop
producing findings, revert the deployment (remove the feature) rather than blanking the config,
which would fail startup.
