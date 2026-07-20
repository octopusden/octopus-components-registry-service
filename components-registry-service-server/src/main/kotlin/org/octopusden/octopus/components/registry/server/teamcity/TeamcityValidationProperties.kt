package org.octopusden.octopus.components.registry.server.teamcity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the TeamCity validation run, bound from `teamcity.validation.*`. Supplies the
 * template/step ids the `component-validation` module's `TemplateCatalog` needs (decision D2 —
 * confirm the real ids against the live TeamCity instance) and the enriched-fetch cache TTL.
 *
 * TC connection details (base url / credentials) are shared with the sync engine via
 * [TeamcityProperties].
 */
@ConfigurationProperties(prefix = "teamcity.validation")
class TeamcityValidationProperties(
    /** Enable the weekly cron? Validation also runs after each successful sync + on demand. */
    val enabled: Boolean = false,
    /** Build template id whose configs are "our Gradle build" */
    val gradleBuildTemplateId: String = "CDGradleBuild",
    /** Build template id whose configs are "our Maven build" */
    val mavenBuildTemplateId: String = "CDJavaMavenBuild",
    /** Release-family templates excluded from "not attached to build template". */
    val releaseFamilyTemplateIds: Set<String> =
        setOf("CDRelease", "CdReleaseCandidateNew", "CdReleaseChecklistValidation"),
    /** Default build step id `X` for the Gradle build template */
    val gradleDefaultBuildStepId: String = "",
    /** Default build step id `X` for the Maven build template */
    val mavenDefaultBuildStepId: String = "",
    /** Enriched-fetch cache TTL, minutes (see design §2). */
    val cacheTtlMinutes: Long = 30,
)
