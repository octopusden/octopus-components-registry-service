package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentVersionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ComponentVersionRepository : JpaRepository<ComponentVersionEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentVersionEntity>

    fun findByComponentName(componentName: String): List<ComponentVersionEntity>
}
