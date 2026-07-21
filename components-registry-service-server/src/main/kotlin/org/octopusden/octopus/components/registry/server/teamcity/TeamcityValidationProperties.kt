package org.octopusden.octopus.components.registry.server.teamcity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the TeamCity validation run, bound from `teamcity.validation.*`. Supplies the
 * template/step ids the `component-validation` module's `TemplateCatalog` needs (decision D2 —
 * confirm the real ids against the live TeamCity instance) and the enriched-fetch cache TTL.
 *
 * TC connection details (base url / credentials) are shared with the sync engine via
 * [TeamcityProperties].
 *
 * There is no `enabled` toggle: validation always runs after a successful sync and on demand,
 * there is no scheduler to gate. The template/step-id properties below have **no defaults on
 * purpose** — a blank default-build-step id silently made `OVERRIDES_DEFAULT_BUILD_STEP` (and
 * anything depending on it) report a false "clean" result instead of failing to boot, so every
 * environment that wants correct results must declare real values; there is nothing sensible to
 * default them to.
 */
@ConfigurationProperties(prefix = "teamcity.validation")
class TeamcityValidationProperties(
    /** Build template id whose configs are "our Gradle build" */
    val gradleBuildTemplateId: String,
    /** Build template id whose configs are "our Maven build" */
    val mavenBuildTemplateId: String,
    /** Release-family templates excluded from "not attached to build template". */
    val releaseFamilyTemplateIds: Set<String>,
    /** Default build step id `X` for the Gradle build template */
    val gradleDefaultBuildStepId: String,
    /** Default build step id `X` for the Maven build template */
    val mavenDefaultBuildStepId: String,
    /** Enriched-fetch cache TTL, minutes (see design §2). Not correctness-sensitive; kept defaulted. */
    val cacheTtlMinutes: Long = 30,
)
