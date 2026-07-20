package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.teamcity.TeamcityValidationProperties
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.springframework.stereotype.Component

/** Supplies the module's `TemplateCatalog` from `teamcity.validation.*` config (decision D2). */
@Component
class ConfigTemplateCatalog(
    properties: TeamcityValidationProperties,
) : TemplateCatalog {
    override val gradleBuildTemplateId = properties.gradleBuildTemplateId
    override val mavenBuildTemplateId = properties.mavenBuildTemplateId
    override val releaseFamilyTemplateIds = properties.releaseFamilyTemplateIds

    private val gradleDefaultStepId = properties.gradleDefaultBuildStepId.ifBlank { null }
    private val mavenDefaultStepId = properties.mavenDefaultBuildStepId.ifBlank { null }

    override fun defaultBuildStepId(templateId: String): String? =
        when (templateId) {
            gradleBuildTemplateId -> gradleDefaultStepId
            mavenBuildTemplateId -> mavenDefaultStepId
            else -> null
        }
}
