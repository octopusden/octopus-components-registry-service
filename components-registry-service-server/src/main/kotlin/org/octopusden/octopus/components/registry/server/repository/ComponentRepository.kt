package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentRepository :
    JpaRepository<ComponentEntity, UUID>,
    JpaSpecificationExecutor<ComponentEntity> {
    fun findByComponentKey(componentKey: String): ComponentEntity?

    fun findByComponentKeyIn(componentKeys: Collection<String>): List<ComponentEntity>

    fun findByComponentKeyAndArchivedFalse(componentKey: String): ComponentEntity?

    fun findByArchivedFalse(): List<ComponentEntity>

    fun existsByComponentKey(componentKey: String): Boolean

    @Query(
        "SELECT DISTINCT c.componentOwner FROM ComponentEntity c " +
            "WHERE c.componentOwner IS NOT NULL AND c.componentOwner <> '' " +
            "ORDER BY c.componentOwner",
    )
    fun findDistinctOwners(): List<String>
}
