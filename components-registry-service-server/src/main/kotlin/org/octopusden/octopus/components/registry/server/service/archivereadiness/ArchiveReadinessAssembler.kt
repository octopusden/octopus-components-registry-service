package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ArchiveReadinessEntry
import org.octopusden.octopus.components.registry.server.dto.v4.ArchiveReadinessResponse
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.components.registry.server.dto.v4.TargetKind
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.VcsSettingsEntryRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

private const val TEAMCITY_LIVENESS_REASON = "TeamCity system could not be consulted"
private const val VCS_LIVENESS_REASON = "VCS system could not be consulted"
private const val JIRA_ISSUES_LIVENESS_REASON = "Jira issue search could not be consulted"
private const val JIRA_PROJECT_LIVENESS_REASON = "Jira project client could not be consulted"

// SYS-047: injects JPA repositories directly, so it must be dropped in no-db mode too — see
// ConditionalOnDatabaseEnabled's kdoc ("or another bean so annotated").

/**
 * Orchestrator for `GET rest/api/4/components/{idOrName}/archive-readiness`: discovers a
 * component's targets, applies liveness gating (design.md decision 12/13), calls the per-kind
 * checkers, dedupes by target identity (decision 16), and assembles the final response.
 *
 * This is read-only — nothing here writes to the registry or to any external system.
 */
@ConditionalOnDatabaseEnabled
@Service
class ArchiveReadinessAssembler(
    private val versionLineRepository: VersionLineRepository,
    private val componentConfigurationRepository: ComponentConfigurationRepository,
    private val vcsSettingsEntryRepository: VcsSettingsEntryRepository,
    private val livenessProbe: LivenessProbe,
    private val repositoryChecker: RepositoryChecker,
    private val teamcityChecker: TeamcityChecker,
    private val jiraIssuesChecker: JiraIssuesChecker,
    private val jiraProjectChecker: JiraProjectChecker,
    private val pairResolver: JiraEffectivePairResolver,
) {
    private val log = LoggerFactory.getLogger(ArchiveReadinessAssembler::class.java)

    fun assemble(component: ComponentEntity): ArchiveReadinessResponse {
        val componentId = requireNotNull(component.id) { "Cannot assemble archive-readiness for an unpersisted component" }
        log.info("Assembling archive-readiness for component {} ({})", component.componentKey, componentId)
        // Probed exactly once per call — never once per target (design.md decision 12).
        val snapshot = livenessProbe.probe()

        val entries = mutableListOf<ArchiveReadinessEntry>()
        entries += teamcityEntries(component, componentId, snapshot)
        entries += repositoryEntries(component, componentId, snapshot)
        entries += jiraIssuesEntries(componentId, snapshot)
        entries += jiraProjectEntries(componentId, snapshot)

        val ready = entries.none { it.outcome == Outcome.FAILED || it.outcome == Outcome.UNKNOWN }
        log.info(
            "Archive-readiness for component {} ({}): {} entries ({}), ready={}",
            component.componentKey,
            componentId,
            entries.size,
            TargetKind.entries.joinToString(", ") { kind ->
                "$kind=${entries.count { it.targetKind == kind }}"
            },
            ready,
        )
        return ArchiveReadinessResponse(ready = ready, entries = entries)
    }

    /** Dedup by TeamCity project id — two version lines on the same project yield one entry (decision 16). */
    private fun teamcityEntries(
        component: ComponentEntity,
        componentId: UUID,
        snapshot: LivenessSnapshot,
    ): List<ArchiveReadinessEntry> {
        if (!snapshot.teamcityConfigured) {
            log.info("TEAMCITY_PROJECT: skipped for component {} — TeamCity not configured", componentId)
            return emptyList()
        }
        val projectIds =
            versionLineRepository
                .findByComponentId(componentId)
                .map { it.teamcityProject.projectId }
                .distinct()
        if (projectIds.isEmpty()) {
            log.info("TEAMCITY_PROJECT: component {} has no version lines — nothing to check", componentId)
        }
        return if (!snapshot.teamcityLive) {
            // Liveness down: every target still gets an entry, but all share ONE reason and
            // NONE of them calls teamcityChecker (no wasteful per-target timeouts).
            projectIds.map { unknownEntry(TargetKind.TEAMCITY_PROJECT, it, TEAMCITY_LIVENESS_REASON) }
        } else {
            projectIds.map { id ->
                val result = teamcityChecker.check(CheckTarget(id, componentId, component.componentKey))
                toEntry(TargetKind.TEAMCITY_PROJECT, id, result)
            }
        }
    }

    /**
     * Dedup by canonical URL (decision 10/16), but the RAW stored `vcsPath` — not the
     * canonical form — is both the entry's `targetId` and what gets passed to
     * `repositoryChecker.check`. Canonicalization is for comparison only.
     */
    private fun repositoryEntries(
        component: ComponentEntity,
        componentId: UUID,
        snapshot: LivenessSnapshot,
    ): List<ArchiveReadinessEntry> {
        if (!snapshot.vcsConfigured) {
            log.info("REPOSITORY: skipped for component {} — VCS not configured", componentId)
            return emptyList()
        }
        val configRows = componentConfigurationRepository.findByComponentId(componentId)
        val rawUrlByCanonical = LinkedHashMap<String, String>()
        configRows.forEach { row ->
            val rowId = requireNotNull(row.id) { "Cannot resolve VCS entries for an unpersisted configuration row" }
            vcsSettingsEntryRepository.findByComponentConfigurationId(rowId).forEach { entry ->
                val canonical = VcsUrlCanonicalizer.canonicalize(entry.vcsPath)
                rawUrlByCanonical.putIfAbsent(canonical, entry.vcsPath)
            }
        }
        val rawUrls = rawUrlByCanonical.values
        if (rawUrls.isEmpty()) {
            log.info(
                "REPOSITORY: component {} has {} configuration row(s) but no VCS entries on any of them — nothing to check",
                componentId,
                configRows.size,
            )
        }
        return if (!snapshot.vcsLive) {
            rawUrls.map { unknownEntry(TargetKind.REPOSITORY, it, VCS_LIVENESS_REASON) }
        } else {
            rawUrls.map { rawUrl ->
                val result = repositoryChecker.check(CheckTarget(rawUrl, componentId, component.componentKey))
                toEntry(TargetKind.REPOSITORY, rawUrl, result)
            }
        }
    }

    /** One entry per effective (project key, prefix) pair (decision 15). `sharedWith` is always empty. */
    private fun jiraIssuesEntries(
        componentId: UUID,
        snapshot: LivenessSnapshot,
    ): List<ArchiveReadinessEntry> {
        if (!snapshot.jiraIssuesConfigured) {
            log.info("JIRA_ISSUES: skipped for component {} — Jira issue-search not configured", componentId)
            return emptyList()
        }
        val pairs = pairResolver.pairsFor(componentId)
        if (pairs.isEmpty()) {
            log.info("JIRA_ISSUES: component {} has no effective (project key, prefix) pairs — nothing to check", componentId)
        }
        return if (!snapshot.jiraIssuesLive) {
            pairs.map { (projectKey, prefix) ->
                unknownEntry(TargetKind.JIRA_ISSUES, jiraIssuesTargetId(projectKey, prefix), JIRA_ISSUES_LIVENESS_REASON)
            }
        } else {
            pairs.map { (projectKey, prefix) ->
                val result = jiraIssuesChecker.checkPair(projectKey, prefix, componentId)
                ArchiveReadinessEntry(
                    targetKind = TargetKind.JIRA_ISSUES,
                    targetId = jiraIssuesTargetId(projectKey, prefix),
                    targetUrl = null,
                    outcome = result.outcome,
                    reason = result.reason,
                    reasonKind = result.reasonKind,
                    // Belt-and-suspenders: JIRA_ISSUES never carries sharedWith, even if a
                    // checker result happened to populate it (it shouldn't, per Task 7).
                    sharedWith = emptyList(),
                    openIssues = result.openIssues,
                )
            }
        }
    }

    /** One entry per distinct project key among the component's effective pairs (decision 15). */
    private fun jiraProjectEntries(
        componentId: UUID,
        snapshot: LivenessSnapshot,
    ): List<ArchiveReadinessEntry> {
        if (!snapshot.jiraProjectConfigured) {
            log.info("JIRA_PROJECT: skipped for component {} — Jira project-read not configured", componentId)
            return emptyList()
        }
        val projectKeys = pairResolver.pairsFor(componentId).map { it.first }.distinct()
        if (projectKeys.isEmpty()) {
            log.info("JIRA_PROJECT: component {} has no effective project keys — nothing to check", componentId)
        }
        return if (!snapshot.jiraProjectLive) {
            projectKeys.map { unknownEntry(TargetKind.JIRA_PROJECT, it, JIRA_PROJECT_LIVENESS_REASON) }
        } else {
            projectKeys.map { key ->
                val result = jiraProjectChecker.checkProject(key, componentId)
                toEntry(TargetKind.JIRA_PROJECT, key, result)
            }
        }
    }

    private fun jiraIssuesTargetId(
        projectKey: String,
        prefix: String?,
    ): String = if (prefix != null) "$projectKey:$prefix" else projectKey

    private fun toEntry(
        kind: TargetKind,
        targetId: String,
        result: CheckResult,
    ): ArchiveReadinessEntry =
        ArchiveReadinessEntry(
            targetKind = kind,
            targetId = targetId,
            targetUrl = null,
            outcome = result.outcome,
            reason = result.reason,
            reasonKind = result.reasonKind,
            sharedWith = result.sharedWith,
            openIssues = result.openIssues,
        )

    // Called only from the liveness-gating paths above (design.md decision 12/13): the
    // connection itself could not be consulted, so every target it would have checked is
    // UNKNOWN for the same reason — SYSTEM_UNAVAILABLE, never REGISTRY_DATA or NOT_CONFIGURED,
    // since there is no per-target CheckResult to classify it from.
    private fun unknownEntry(
        kind: TargetKind,
        targetId: String,
        reason: String,
    ): ArchiveReadinessEntry =
        ArchiveReadinessEntry(
            targetKind = kind,
            targetId = targetId,
            targetUrl = null,
            outcome = Outcome.UNKNOWN,
            reason = reason,
            reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
            sharedWith = emptyList(),
            openIssues = emptyList(),
        )
}
