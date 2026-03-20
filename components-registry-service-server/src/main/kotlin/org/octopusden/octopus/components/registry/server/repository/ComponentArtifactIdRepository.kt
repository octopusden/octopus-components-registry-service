package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ComponentArtifactIdRepository : JpaRepository<ComponentArtifactIdEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentArtifactIdEntity>
}
