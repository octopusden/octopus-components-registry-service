package org.octopusden.octopus.components.registry.server.teamcity.validation

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.repository.TeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationResult
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.octopusden.octopus.validation.validators.TeamCityValidators
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Runs the `component-validation` module over the TeamCity projects the registry knows about, and
 * persists the WARNING/ERROR findings.
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcityValidationService(
    private val teamcityProjectRepository: TeamcityProjectRepository,
    private val teamcityValidationRepository: TeamcityValidationRepository,
    private val fetcher: EnrichedTcProjectFetcher,
    private val mapper: TeamcityProjectMapper,
    templateCatalog: TemplateCatalog,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = KotlinLogging.logger {}

    // The module suite is stateless given a catalog — build once.
    private val validators = TeamCityValidators(templateCatalog)

    @Suppress("TooGenericExceptionCaught")
    fun validate(): TeamcityValidationResult {
        val knownProjectIds = teamcityProjectRepository.findDistinctProjectIdsSafely()
        log.info { "TC validation starting: ${knownProjectIds.size} project ids in scope" }

        var succeeded = 0
        var failed = 0
        var projectsWithIssues = 0
        val errors = mutableListOf<String>()

        for (projectId in knownProjectIds) {
            try {
                val external = fetcher.fetch(projectId)
                if (external == null) {
                    // Not returned by TC (archived/removed/renamed): keep the project's existing rows.
                    failed++
                    errors.add("Project '$projectId' not returned by TeamCity; kept previous findings")
                    continue
                }
                val project = mapper.toModel(external)
                val issues =
                    validators
                        .validate(project)
                        .filter { it.status == Status.WARNING || it.status == Status.ERROR }
                replaceFindings(projectId, issues)
                succeeded++
                if (issues.isNotEmpty()) projectsWithIssues++
            } catch (e: Exception) {
                // Per-project failure: keep old rows, keep going.
                failed++
                val msg = "Failed to validate project '$projectId': ${e.message}"
                log.error(e) { msg }
                errors.add(msg)
            }
        }

        val removed = removeStaleProjects(knownProjectIds)

        log.info {
            "TC validation done: scanned=${knownProjectIds.size}, succeeded=$succeeded, failed=$failed, " +
                "projectsWithIssues=$projectsWithIssues, removed=$removed, errors=${errors.size}"
        }
        return TeamcityValidationResult(
            scanned = knownProjectIds.size,
            succeeded = succeeded,
            failed = failed,
            projectsWithIssues = projectsWithIssues,
            removed = removed,
            errors = errors.toList(),
        )
    }

    /** Full per-project replace, in its own transaction, so the flip is atomic for readers. */
    private fun replaceFindings(
        projectId: String,
        issues: List<org.octopusden.octopus.validation.core.ValidationResult>,
    ) {
        val now = Instant.now()
        transactionTemplate.executeWithoutResult {
            teamcityValidationRepository.deleteByProjectId(projectId)
            if (issues.isNotEmpty()) {
                teamcityValidationRepository.saveAll(
                    issues.map { result ->
                        TeamcityValidationEntity(
                            projectId = projectId,
                            type = result.type.id,
                            status = result.status.name,
                            message = result.message,
                            updatedAt = now,
                        )
                    },
                )
            }
        }
    }

    /** Delete findings for projects no longer known. Skips when the known set is empty (never wipes all). */
    private fun removeStaleProjects(knownProjectIds: Set<String>): Int {
        if (knownProjectIds.isEmpty()) {
            log.warn { "TC validation: known project set is empty; skipping stale-row cleanup" }
            return 0
        }
        val removed = teamcityValidationRepository.findDistinctStoredProjectIds().toSet() - knownProjectIds
        if (removed.isNotEmpty()) {
            transactionTemplate.executeWithoutResult { teamcityValidationRepository.deleteByProjectIdIn(removed) }
        }
        return removed.size
    }

    private fun TeamcityProjectRepository.findDistinctProjectIdsSafely(): Set<String> =
        findDistinctProjectIds().filter { it.isNotBlank() }.toSet()
}
