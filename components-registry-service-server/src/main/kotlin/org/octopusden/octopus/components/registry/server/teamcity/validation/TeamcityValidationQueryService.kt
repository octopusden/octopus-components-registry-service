package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentTeamcityValidationRow
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityValidationSummaryResponse
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
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
) {
    @Transactional(readOnly = true)
    fun list(
        type: String?,
        status: String?,
        component: String?,
    ): List<ComponentTeamcityValidationRow> {
        val findings =
            teamcityValidationRepository.findAll().filter { finding ->
                (type == null || finding.type.equals(type, ignoreCase = true)) &&
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
        val rows = list(type = null, status = null, component = null)

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
     *
     * A component can reach the same project through more than one version line (the schema
     * permits it even though the normal TC sync path avoids it in practice; manual v4 curation
     * can still create it). Without a `distinct` here, such a project/component pair would be
     * counted once per version line, inflating `list()`/`findings` counts. `distinctBy` on
     * `(projectId, componentId)` keeps this query correct for every schema-valid state.
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
