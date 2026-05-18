package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentLabelRepository : JpaRepository<ComponentLabelEntity, ComponentLabelId> {
    fun findByComponentId(componentId: UUID): List<ComponentLabelEntity>
}
