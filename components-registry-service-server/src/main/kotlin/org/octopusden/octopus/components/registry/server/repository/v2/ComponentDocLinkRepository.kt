package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentDocLinkEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentDocLinkRepository : JpaRepository<ComponentDocLinkEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentDocLinkEntity>
    fun findByDocComponentKey(docComponentKey: String): List<ComponentDocLinkEntity>
}
