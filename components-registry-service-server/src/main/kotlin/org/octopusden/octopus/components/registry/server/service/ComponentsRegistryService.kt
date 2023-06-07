package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO

interface ComponentsRegistryService {
    fun getComponentsRegistryStatus(): ServiceStatusDTO
    fun updateConfigCache(): Long
}