package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.core.dto.CopyrightDTO

interface CopyrightService {
    fun getCopyright(component: String): CopyrightDTO
}
