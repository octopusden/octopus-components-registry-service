package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.springframework.stereotype.Service
import java.util.UUID

// SYS-047: injects JPA repositories directly, so it must be dropped in no-db mode too — see
// ConditionalOnDatabaseEnabled's kdoc ("or another bean so annotated").
@ConditionalOnDatabaseEnabled
@Service
class SharingHelper(
    private val versionLineRepository: VersionLineRepository,
    private val componentRepository: ComponentRepository,
    private val componentConfigurationRepository: ComponentConfigurationRepository,
) {
    fun sharedWithForRepo(
        canonicalUrl: String,
        excludeComponentId: UUID,
    ): List<String> =
        componentRepository
            .findNonArchivedComponentVcsPaths(excludeComponentId)
            .filter { VcsUrlCanonicalizer.canonicalize(it.vcsPath) == canonicalUrl }
            .map { it.componentKey }
            .distinct()

    fun sharedWithForTcProject(
        projectId: String,
        descendantIds: Set<String>,
        excludeComponentId: UUID,
    ): List<String> {
        val registryIds = versionLineRepository.findDistinctLinkedProjectIds().toSet()
        val relevant = registryIds.intersect(descendantIds + projectId)
        if (relevant.isEmpty()) return emptyList()
        return versionLineRepository
            .findByProjectIdsWithComponent(relevant.toList())
            .filter { vl -> !vl.component.archived && vl.component.id != excludeComponentId }
            .map { it.component.componentKey }
            .distinct()
    }

    fun sharedWithForJiraProject(
        projectKey: String,
        excludeComponentId: UUID,
    ): List<String> {
        val rows = componentConfigurationRepository.findAllNonArchivedJiraRows()
        val jiraRows = rows.map { r ->
            JiraRowView(r.componentKey, r.versionRange, r.rowType, r.overriddenAttribute, r.projectKey, r.versionPrefix)
        }
        val pairsByComponent = computeEffectiveJiraPairs(jiraRows)
        return componentRepository
            .findAll()
            .filter { !it.archived && it.id != excludeComponentId }
            .filter { comp ->
                pairsByComponent[comp.componentKey]?.any { (pk, _) -> pk == projectKey } == true
            }.map { it.componentKey }
    }
}
