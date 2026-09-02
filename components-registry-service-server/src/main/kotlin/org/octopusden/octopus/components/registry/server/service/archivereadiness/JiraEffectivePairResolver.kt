package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.springframework.stereotype.Service
import java.util.UUID

// SYS-047: injects JPA repositories directly, so it must be dropped in no-db mode too — see
// ConditionalOnDatabaseEnabled's kdoc ("or another bean so annotated").
@ConditionalOnDatabaseEnabled
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

    /** Every effective (project key, prefix) pair, across every component in the registry. */
    private fun allPairsRegistryWide(): List<Pair<String, String?>> {
        val rows = configRepo.findAllNonArchivedJiraRows()
        val jiraRows = rows.map { r ->
            JiraRowView(r.componentKey, r.versionRange, r.rowType, r.overriddenAttribute, r.projectKey, r.versionPrefix)
        }
        return computeEffectiveJiraPairs(jiraRows).values.flatten()
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
    fun hasNullPrefixConflict(projectKey: String): Boolean =
        allPairsRegistryWide().count { (pk, prefix) -> pk == projectKey && prefix == null } > 1

    /**
     * True when MORE THAN ONE effective pair — registry-wide, any component, any version range —
     * has this [projectKey], regardless of prefix. Used by [JiraIssuesChecker] to decide whether
     * a null-prefix pair is the project's SOLE claimant (scope = whole project, spec.md's "A sole
     * claim on a project is scoped by the whole project") or shares the project key with another
     * pair that has its own non-null prefix (scope = bare-version-pattern issues only — a
     * null-prefix conflict, where two pairs both claim null, is already ruled out by
     * [hasNullPrefixConflict] before this is consulted). Counting is sufficient: if exactly one
     * pair claims [projectKey], that pair IS the one being checked, so "sole claim" holds without
     * needing to exclude it by componentId explicitly.
     */
    fun hasOtherPairOnProjectKey(projectKey: String): Boolean = allPairsRegistryWide().count { (pk, _) -> pk == projectKey } > 1
}
