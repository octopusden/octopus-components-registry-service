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
     * Distinct group keys of groups that own at least one component **visible in the v4
     * list**. Source for the `/meta/group-keys` dropdown (SYS-046). The EXISTS guard keeps
     * memberless groups out; the extra `isFake = false OR componentKey <> groupKey`
     * predicate mirrors the always-on fake-self-link exclusion in
     * `ComponentManagementServiceImpl.buildSpecification`, so a fake aggregator whose only
     * member is its own hidden self-linked stub is NOT advertised (its `?groupKey=` page
     * would be empty — a dead option). A fake group with ≥1 real child still appears.
     */
    @Query(
        "SELECT DISTINCT g.groupKey FROM ComponentGroupEntity g " +
            "WHERE g.groupKey IS NOT NULL AND TRIM(g.groupKey) <> '' " +
            "AND EXISTS (" +
            "SELECT 1 FROM ComponentEntity c WHERE c.componentGroup = g " +
            "AND (g.isFake = false OR c.componentKey <> g.groupKey)" +
            ") " +
            "ORDER BY g.groupKey",
    )
    fun findDistinctGroupKeys(): List<String>
}
