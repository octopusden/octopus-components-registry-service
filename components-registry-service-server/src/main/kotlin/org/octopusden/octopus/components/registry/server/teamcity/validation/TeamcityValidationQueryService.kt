package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentTeamcityValidationRow
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityValidationSummaryResponse
import org.octopusden.octopus.components.registry.server.mapper.composeTeamcityProjectUrl
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read side of stored findings, component-centric: joins each project-keyed finding back to its
 * owning component(s) via `version_line`. Join done in code (bounded, issues-only set).
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcityValidationQueryService(
    private val teamcityValidationRepository: TeamcityValidationRepository,
    private val versionLineRepository: VersionLineRepository,
    private val teamcityProperties: TeamcityProperties,
) {
    @Transactional(readOnly = true)
    fun list(
        types: List<String>?,
        status: String?,
        component: String?,
    ): List<ComponentTeamcityValidationRow> {
        val typeFilter = types
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val findings =
            teamcityValidationRepository.findAll().filter { finding ->
                (typeFilter == null || finding.type.lowercase() in typeFilter) &&
                    (status == null || finding.status.equals(status, ignoreCase = true))
            }
        if (findings.isEmpty()) return emptyList()

        val componentsByProject = componentsByProject(findings.map { it.projectId }.toSet())
        val rows =
            findings.flatMap { finding ->
                componentsByProject[finding.projectId].orEmpty().map { (componentId, componentName) ->
                    ComponentTeamcityValidationRow(
                        componentId = componentId,
                        componentName = componentName,
                        projectId = finding.projectId,
                        projectUrl = composeTeamcityProjectUrl(teamcityProperties.baseUrl, finding.projectId),
                        type = finding.type,
                        status = finding.status,
                        message = finding.message,
                        updatedAt = finding.updatedAt,
                    )
                }
            }
        return if (component == null) {
            rows
        } else {
            rows.filter { it.componentName.contains(component, ignoreCase = true) }
        }
    }

    @Transactional(readOnly = true)
    fun summary(): TeamcityValidationSummaryResponse {
        val rows = list(types = null, status = null, component = null)

        fun distinctComponents(subset: List<ComponentTeamcityValidationRow>): Int = subset.map { it.componentId }.toSet().size
        return TeamcityValidationSummaryResponse(
            componentsWithIssues = distinctComponents(rows),
            findings = rows.size,
            byType = rows.groupBy { it.type }.mapValues { (_, v) -> distinctComponents(v) },
            byStatus = rows.groupBy { it.status }.mapValues { (_, v) -> distinctComponents(v) },
        )
    }

    /**
     * project id -> the components (id + key) that own it, via version_line.
     */
    private fun componentsByProject(projectIds: Set<String>): Map<String, List<Pair<UUID, String>>> =
        versionLineRepository
            .findByProjectIdsWithComponent(projectIds)
            .distinctBy { it.teamcityProject.projectId to it.component.id }
            .groupBy(
                { it.teamcityProject.projectId },
                { requireNotNull(it.component.id) { "component id must not be null" } to it.component.componentKey },
            )
}
