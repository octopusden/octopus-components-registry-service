package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.VcsSettingsEntryRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SharingHelper(
    private val versionLineRepository: VersionLineRepository,
    private val componentRepository: ComponentRepository,
    private val vcsSettingsEntryRepository: VcsSettingsEntryRepository,
    private val componentConfigurationRepository: ComponentConfigurationRepository,
) {
    fun sharedWithForRepo(
        canonicalUrl: String,
        excludeComponentId: UUID,
    ): List<String> =
        componentRepository
            .findAll()
            .filter { !it.archived && it.id != excludeComponentId }
            .filter { comp ->
                componentConfigurationRepository
                    .findByComponentId(comp.id!!)
                    .flatMap { cfg -> vcsSettingsEntryRepository.findByComponentConfigurationId(cfg.id!!) }
                    .any { entry -> VcsUrlCanonicalizer.canonicalize(entry.vcsPath) == canonicalUrl }
            }.map { it.componentKey }

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
