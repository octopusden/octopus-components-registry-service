package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class JiraEffectivePairResolver(
    private val configRepo: ComponentConfigurationRepository,
    private val componentRepository: ComponentRepository,
) {
    /** This component's own effective (project key, prefix) pairs — base + every version-range override. */
    fun pairsFor(componentId: UUID): Set<Pair<String, String?>> {
        val component = componentRepository.findById(componentId).orElse(null) ?: return emptySet()
        val rows: List<ComponentConfigurationEntity> = configRepo.findByComponentId(componentId)
        val jiraRows = rows.map { r ->
            JiraRowView(
                componentKey = component.componentKey,
                versionRange = r.versionRange,
                rowType = r.rowType,
                overriddenAttribute = r.overriddenAttribute,
                projectKey = r.jiraProjectKey,
                versionPrefix = r.jiraVersionPrefix,
            )
        }
        return computeEffectiveJiraPairs(jiraRows)[component.componentKey] ?: emptySet()
    }

    /**
     * True when TWO OR MORE pairs — across every component in the registry, including this
     * component's own other version ranges — claim a null prefix on [projectKey]. The registry's
     * own cross-component uniqueness check is expected to prevent this, so it is a genuine data
     * anomaly when it happens, not a normal case (design.md decision 15's "genuine null-prefix
     * conflict"). This is what makes a null-prefix pair's scope UNKNOWN instead of a bare-pattern
     * search — see JiraIssuesChecker below. Not needed for JIRA_PROJECT (that entry doesn't
     * depend on prefix at all).
     */
    fun hasNullPrefixConflict(projectKey: String): Boolean {
        val rows = configRepo.findAllNonArchivedJiraRows()
        val jiraRows = rows.map { r ->
            JiraRowView(r.componentKey, r.versionRange, r.rowType, r.overriddenAttribute, r.projectKey, r.versionPrefix)
        }
        val allPairs = computeEffectiveJiraPairs(jiraRows).values.flatten()
        return allPairs.count { (pk, prefix) -> pk == projectKey && prefix == null } > 1
    }
}
