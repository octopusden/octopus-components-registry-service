package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TeamcityProjectRepository : JpaRepository<TeamcityProjectEntity, UUID> {
    fun findByProjectId(projectId: String): TeamcityProjectEntity?
}
