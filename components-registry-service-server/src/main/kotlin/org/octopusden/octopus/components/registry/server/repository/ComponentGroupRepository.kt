package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentGroupRepository : JpaRepository<ComponentGroupEntity, UUID> {
    fun findByGroupKey(groupKey: String): ComponentGroupEntity?

    /**
     * Distinct group keys of groups that own at least one component. Source for the
     * `/meta/group-keys` dropdown (SYS-046). The EXISTS guard keeps memberless groups
     * out, so the dropdown never advertises a key the `?groupKey=` filter can't match.
     */
    @Query(
        "SELECT DISTINCT g.groupKey FROM ComponentGroupEntity g " +
            "WHERE g.groupKey IS NOT NULL AND TRIM(g.groupKey) <> '' " +
            "AND EXISTS (SELECT 1 FROM ComponentEntity c WHERE c.componentGroup = g) " +
            "ORDER BY g.groupKey",
    )
    fun findDistinctGroupKeys(): List<String>
}
