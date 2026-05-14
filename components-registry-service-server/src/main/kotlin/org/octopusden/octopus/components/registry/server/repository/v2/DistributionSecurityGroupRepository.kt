package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.DistributionSecurityGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionSecurityGroupRepository : JpaRepository<DistributionSecurityGroupEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<DistributionSecurityGroupEntity>
}
