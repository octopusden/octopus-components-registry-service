package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentGroupRepository : JpaRepository<ComponentGroupEntity, UUID> {
    fun findByGroupKey(groupKey: String): ComponentGroupEntity?
}
