package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.server.mapper.toDTO
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.escrow.config.ConfigHelper
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/2/common")
class CommonControllerV2(
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val environment: Environment
) {

    val configHelper: ConfigHelper =
        ConfigHelper(this.environment)

    @GetMapping("jira-component-version-ranges", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRangeDTO> {
        return componentRegistryResolver.getAllJiraComponentVersionRanges()
            .map { it.toDTO() }
            .toSet()
    }

    @GetMapping("dependency-aliases", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getDependencyAliasToComponentMapping(): Map<String, String> = componentRegistryResolver.getDependencyMapping()

    @GetMapping("supported-groups", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSupportedGroupIds(): Set<String> {
        return configHelper.supportedGroupIds().toSet()
    }
}
