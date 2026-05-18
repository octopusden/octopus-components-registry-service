package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentSystemRepository : JpaRepository<ComponentSystemEntity, ComponentSystemId> {
    fun findByComponentId(componentId: UUID): List<ComponentSystemEntity>
}
