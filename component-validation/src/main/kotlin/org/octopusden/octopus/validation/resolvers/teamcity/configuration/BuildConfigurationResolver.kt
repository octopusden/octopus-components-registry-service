package org.octopusden.octopus.validation.resolvers.teamcity.configuration

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject

interface BuildConfigurationResolver {
    fun attachedToBuildTemplate(project: TeamcityProject): List<BuildConfiguration>

    /** Configs not attached to a build template — excludes the release-family templates. */
    fun notAttachedToBuildTemplate(project: TeamcityProject): List<BuildConfiguration>
}
