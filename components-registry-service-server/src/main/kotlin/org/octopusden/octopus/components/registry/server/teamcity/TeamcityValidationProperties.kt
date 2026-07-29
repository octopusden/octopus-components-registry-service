package org.octopusden.octopus.components.registry.server.teamcity

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Configuration for the TeamCity validation run, bound from `teamcity.validation.*`. Supplies the
 * template/step ids the `component-validation` module's `TemplateCatalog` needs (decision D2 —
 * confirm the real ids against the live TeamCity instance) and the enriched-fetch cache TTL.
 *
 * TC connection details (base url / credentials) are shared with the sync engine via
 * [TeamcityProperties].
 *
 * ## Configuration is mandatory
 *
 * All five template/step-id fields are **required and must be non-blank** (`@Validated` + Bean
 * Validation `@NotBlank`/`@NotEmpty`). The application **fails to start** if any is blank or empty.
 * `releaseFamilyTemplateIds` must additionally contain no blank/whitespace-only elements (enforced
 * by [isReleaseFamilyTemplateIdsAllNonBlank]): a blank id matches no real template, so a release
 * configuration would fall through into `notAttachedToBuildTemplate` and produce false WARNING
 * noise — exactly what the fail-fast contract exists to prevent. (An `@AssertTrue` getter is used
 * rather than `Set<@NotBlank String>` because Kotlin type-use annotations on a generic argument are
 * not reliably surfaced to Hibernate Validator as container-element constraints; the method must be
 * `is`-prefixed so the validator treats it as a bean property and actually evaluates it.)
 */
@Validated
@ConfigurationProperties(prefix = "teamcity.validation")
class TeamcityValidationProperties(
    /** Build template id whose configs are "our Gradle build". */
    @field:NotBlank
    val gradleBuildTemplateId: String,
    /** Build template id whose configs are "our Maven build". */
    @field:NotBlank
    val mavenBuildTemplateId: String,
    /** Release-family templates excluded from "not attached to build template". Non-empty, no blank elements. */
    @field:NotEmpty
    val releaseFamilyTemplateIds: Set<String>,
    /** Default build step id `X` for the Gradle build template. */
    @field:NotBlank
    val gradleDefaultBuildStepId: String,
    /** Default build step id `X` for the Maven build template. */
    @field:NotBlank
    val mavenDefaultBuildStepId: String,
    /** Enriched-fetch cache TTL, minutes (see design §2). Not correctness-sensitive; kept defaulted. */
    val cacheTtlMinutes: Long = 30,
) {
    /**
     * No `release-family-template-ids` element may be blank/whitespace-only. An empty set is
     * allowed here (that case is reported by `@NotEmpty` instead), so this fails only on a genuine
     * blank element such as `[""]` or `["  "]`.
     */
    @AssertTrue(message = "release-family-template-ids must not contain blank/whitespace-only entries")
    fun isReleaseFamilyTemplateIdsAllNonBlank(): Boolean = releaseFamilyTemplateIds.all { it.isNotBlank() }
}
