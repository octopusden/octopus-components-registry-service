package org.octopusden.octopus.components.registry.server.service

import org.springframework.core.io.Resource

interface CopyrightService {
    fun getCopyrightAsResource(component: String): Resource
}
