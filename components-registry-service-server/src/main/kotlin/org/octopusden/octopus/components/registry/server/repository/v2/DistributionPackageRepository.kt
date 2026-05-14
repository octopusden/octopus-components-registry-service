package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.DistributionPackageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionPackageRepository : JpaRepository<DistributionPackageEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<DistributionPackageEntity>
}
