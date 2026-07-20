package org.octopusden.octopus.validation.dto.teamcity

/**
 * Knowledge of which build templates are "our" build templates, the release-family templates
 * that are exempt from [org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver.notAttachedToBuildTemplate],
 * and the default build step id per template.
 *
 * This module ships no concrete implementation: the real template/step ids (decision D2) live in
 * a specific TeamCity instance's shared templates, which is a deployment/server concern. A caller
 * (the server, when it wires up [org.octopusden.octopus.validation.validators.TeamCityValidators])
 * supplies a `TemplateCatalog` backed by those real values.
 */
interface TemplateCatalog {
    val gradleBuildTemplateId: String
    val mavenBuildTemplateId: String
    val releaseFamilyTemplateIds: Set<String>

    fun isBuildTemplate(templateId: String): Boolean = templateId == gradleBuildTemplateId || templateId == mavenBuildTemplateId

    fun isReleaseFamily(templateId: String): Boolean = templateId in releaseFamilyTemplateIds

    /** The default build step id for [templateId], or `null` if it isn't one of our templates. */
    fun defaultBuildStepId(templateId: String): String?
}
