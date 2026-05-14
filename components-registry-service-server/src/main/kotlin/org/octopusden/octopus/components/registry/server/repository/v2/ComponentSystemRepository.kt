package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.v2.ComponentSystemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentSystemRepository : JpaRepository<ComponentSystemEntity, ComponentSystemId> {
    fun findByComponentId(componentId: UUID): List<ComponentSystemEntity>
}
