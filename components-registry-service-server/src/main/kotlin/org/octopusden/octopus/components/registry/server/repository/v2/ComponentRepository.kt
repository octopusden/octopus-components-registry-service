package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentRepository : JpaRepository<ComponentEntity, UUID> {
    fun findByComponentKey(componentKey: String): ComponentEntity?
    fun findByComponentKeyAndArchivedFalse(componentKey: String): ComponentEntity?
    fun existsByComponentKey(componentKey: String): Boolean
}
