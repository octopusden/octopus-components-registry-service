package org.octopusden.octopus.components.registry.server.controller

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/4")
class InfoControllerV4(
    private val buildProperties: BuildProperties,
) {
    @GetMapping("/info")
    fun info(): InfoResponse = InfoResponse(name = buildProperties.name, version = buildProperties.version)

    data class InfoResponse(
        val name: String,
        val version: String,
    )
}
