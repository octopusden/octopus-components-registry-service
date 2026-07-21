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
