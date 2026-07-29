package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VersionLineRepository : JpaRepository<VersionLineEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<VersionLineEntity>

    /** Version lines for the given TeamCity project ids, with component + project eagerly fetched. */
    @Query(
        "SELECT vl FROM VersionLineEntity vl " +
            "JOIN FETCH vl.component JOIN FETCH vl.teamcityProject tp WHERE tp.projectId IN :projectIds",
    )
    fun findByProjectIdsWithComponent(projectIds: Collection<String>): List<VersionLineEntity>

    /**
     * Distinct TeamCity project ids currently referenced by at least one version line — i.e.
     * actually linked to a component right now.
     */
    @Query("SELECT DISTINCT vl.teamcityProject.projectId FROM VersionLineEntity vl")
    fun findDistinctLinkedProjectIds(): List<String>
}
