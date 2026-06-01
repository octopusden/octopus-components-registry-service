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

    /**
     * Distinct system codes currently assigned to at least one component.
     *
     * Sourced from the scalar `components.system_code` column (the M:N
     * `component_systems` junction was collapsed to a 1:0..1 reference in
     * this iteration). NOT sourced from the master `SystemEntity` /
     * `systems` table — that's the `/meta/systems/dictionary` endpoint;
     * this endpoint advertises only codes actually in use, parity with
     * `/meta/owners` and `/meta/labels`.
     *
     * Defensively filters null and blank/whitespace-only system_code
     * values, mirroring the IS-NOT-NULL + non-empty guard on
     * `findDistinctOwners`. A stray "" or "   " in the column (from
     * direct DB write) would otherwise surface as an unselectable blank
     * chip in the Portal picker.
     */
    @Query(
        "SELECT DISTINCT c.systemCode FROM ComponentEntity c " +
            "WHERE c.systemCode IS NOT NULL AND TRIM(c.systemCode) <> '' " +
            "ORDER BY c.systemCode",
    )
    fun findDistinctSystemCodes(): List<String>

    /**
     * True when at least one component references [parentId] as its
     * `parentComponent`. Used by the service layer to reject disabling
     * `canBeParent` on a component that still has children.
     */
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END " +
            "FROM ComponentEntity c WHERE c.parentComponent.id = :parentId",
    )
    fun existsByParentComponentId(parentId: UUID): Boolean

    /**
     * All components whose `componentGroup` is [groupId]. Used by migration cleanup
     * (§6.3) to unlink the members of a group that is no longer a true aggregator
     * before deleting the now-orphaned `component_groups` row.
     */
    fun findByComponentGroupId(groupId: UUID): List<ComponentEntity>
}
