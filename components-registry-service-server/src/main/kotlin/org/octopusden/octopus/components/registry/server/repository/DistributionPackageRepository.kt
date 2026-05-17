package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionPackageRepository : JpaRepository<DistributionPackageEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<DistributionPackageEntity>
}
