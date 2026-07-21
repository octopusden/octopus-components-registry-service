package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TeamcityValidationRepository : JpaRepository<TeamcityValidationEntity, TeamcityValidationId> {
    fun findByProjectIdIn(projectIds: Collection<String>): List<TeamcityValidationEntity>

    /**
     * Bulk DELETE issued directly against the DB (not a derived load-then-remove), so it does not
     * leave managed "removed" instances in the persistence context. `clearAutomatically` detaches
     * the context afterward so a same-transaction `saveAll` with the same composite id issues an
     * INSERT (via `persist`) rather than colliding with a stale managed/removed instance and being
     * downgraded to `merge` — the repeated-run bug where a finding type surviving between runs could
     * throw `ObjectDeletedException` or hit insert-before-delete PK ordering.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TeamcityValidationEntity e WHERE e.projectId = :projectId")
    fun deleteByProjectId(
        @Param("projectId") projectId: String,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TeamcityValidationEntity e WHERE e.projectId IN :projectIds")
    fun deleteByProjectIdIn(
        @Param("projectIds") projectIds: Collection<String>,
    ): Int

    /** Distinct project ids that currently have stored findings — used to compute the removed set. */
    @Query("SELECT DISTINCT e.projectId FROM TeamcityValidationEntity e")
    fun findDistinctStoredProjectIds(): List<String>
}
