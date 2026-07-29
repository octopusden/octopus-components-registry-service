package org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver

class DefaultBuildConfigurationResolver(
    private val catalog: TemplateCatalog,
) : BuildConfigurationResolver {
    override fun attachedToBuildTemplate(project: TeamcityProject): List<BuildConfiguration> =
        project.buildConfigurations.filter { config ->
            !config.paused && config.templateIds.any { catalog.isBuildTemplate(it) }
        }

    override fun notAttachedToBuildTemplate(project: TeamcityProject): List<BuildConfiguration> =
        project.buildConfigurations.filter { config ->
            !config.paused && config.templateIds.none { catalog.isBuildTemplate(it) || catalog.isReleaseFamily(it) }
        }
}
