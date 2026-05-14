package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.v2.ComponentRequiredToolId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentRequiredToolRepository : JpaRepository<ComponentRequiredToolEntity, ComponentRequiredToolId> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<ComponentRequiredToolEntity>
}
