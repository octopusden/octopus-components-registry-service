package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface TeamcityValidationRepository : JpaRepository<TeamcityValidationEntity, TeamcityValidationId> {
    fun findByProjectIdIn(projectIds: Collection<String>): List<TeamcityValidationEntity>

    fun deleteByProjectId(projectId: String)

    fun deleteByProjectIdIn(projectIds: Collection<String>)

    /** Distinct project ids that currently have stored findings — used to compute the removed set. */
    @Query("SELECT DISTINCT e.projectId FROM TeamcityValidationEntity e")
    fun findDistinctStoredProjectIds(): List<String>
}
