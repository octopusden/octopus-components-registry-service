package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.EscrowConfigurationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EscrowConfigurationRepository : JpaRepository<EscrowConfigurationEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<EscrowConfigurationEntity>

    fun findByComponentVersionId(componentVersionId: UUID): List<EscrowConfigurationEntity>
}
