package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentTeamcityProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentTeamcityProjectRepository : JpaRepository<ComponentTeamcityProjectEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentTeamcityProjectEntity>

    fun findByProjectId(projectId: String): List<ComponentTeamcityProjectEntity>
}
