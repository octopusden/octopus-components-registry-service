package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.DistributionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DistributionRepository : JpaRepository<DistributionEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<DistributionEntity>

    fun findByComponentVersionId(componentVersionId: UUID): List<DistributionEntity>
}
