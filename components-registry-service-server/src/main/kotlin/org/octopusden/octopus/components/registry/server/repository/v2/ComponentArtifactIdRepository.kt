package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentArtifactIdEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentArtifactIdRepository : JpaRepository<ComponentArtifactIdEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentArtifactIdEntity>
}
