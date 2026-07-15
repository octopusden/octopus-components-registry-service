package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VersionLineRepository : JpaRepository<VersionLineEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<VersionLineEntity>
}
