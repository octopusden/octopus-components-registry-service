package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionDockerImageRepository : JpaRepository<DistributionDockerImageEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<DistributionDockerImageEntity>
}
