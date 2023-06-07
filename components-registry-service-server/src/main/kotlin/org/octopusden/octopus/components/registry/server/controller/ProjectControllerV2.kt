package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.server.mapper.toDTO
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/2/projects")
class ProjectControllerV2(
        private val componentRegistryResolver: ComponentRegistryResolver
) {

    @GetMapping("{projectKey}/versions/{version}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getJiraComponentByProjectAndVersion(@PathVariable("projectKey") projectKey: String,
                                            @PathVariable("version") version: String): JiraComponentVersionDTO {
        LOG.info("Get Jira Component Version: '$projectKey:$version'")
        return componentRegistryResolver.getJiraComponentByProjectAndVersion(projectKey, version).toDTO()
    }

    @GetMapping("{projectKey}/jira-components")
    fun getJiraComponentsByProject(@PathVariable("projectKey") projectKey: String): Set<String> {
        LOG.info("Get Jira Components: '$projectKey'")
        return componentRegistryResolver.getJiraComponentsByProject(projectKey)
    }

    @GetMapping("{projectKey}/jira-component-version-ranges", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getJiraComponentVersionRangesByProject(@PathVariable("projectKey") projectKey: String): Set<JiraComponentVersionRangeDTO> {
        LOG.info("Get Jira Component Version Ranges: '$projectKey'")
        return componentRegistryResolver.getJiraComponentVersionRangesByProject(projectKey)
                .map { it.toDTO() }
                .toSet()
    }

    @GetMapping("{projectKey}/component-distributions", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentsDistributionByJiraProject(@PathVariable("projectKey") projectKey: String): Map<String, DistributionDTO> {
        LOG.info("Get distributions: '$projectKey'")
        return componentRegistryResolver.getComponentsDistributionByJiraProject(projectKey)
                .map { it.key to it.value.toDTO() }
                .toMap()
    }

    @GetMapping("{projectKey}/versions/{version}/vcs-settings", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getVCSSettingForProject(@PathVariable("projectKey") projectKey: String,
                                @PathVariable("version") version: String): VCSSettingsDTO {
        LOG.info("Get VCS Settings: '$projectKey:$version'")
        return componentRegistryResolver.getVCSSettingForProject(projectKey, version).toDTO()
    }

    @GetMapping("{projectKey}/versions/{version}/distribution", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDistributionForProject(@PathVariable("projectKey") projectKey: String,
                                  @PathVariable("version") version: String): DistributionDTO {
        LOG.info("Get distribution: '$projectKey:$version'")
        return componentRegistryResolver.getDistributionForProject(projectKey, version).toDTO()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ProjectControllerV2::class.java)
    }
}
