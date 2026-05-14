package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentTeamcityProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentTeamcityProjectRepository : JpaRepository<ComponentTeamcityProjectEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentTeamcityProjectEntity>
    fun findByProjectId(projectId: String): List<ComponentTeamcityProjectEntity>
}
