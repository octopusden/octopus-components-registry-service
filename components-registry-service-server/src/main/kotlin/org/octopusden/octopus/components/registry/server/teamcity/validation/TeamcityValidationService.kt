package org.octopusden.octopus.components.registry.server.teamcity.validation

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.placement.compileConfigsOf
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
    private val versionLineRepository: VersionLineRepository,
    private val componentConfigurationRepository: ComponentConfigurationRepository,
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
        val knownProjectIds = versionLineRepository.findDistinctLinkedProjectIdsSafely()
        log.info { "TC validation starting: ${knownProjectIds.size} project ids in scope" }

        // The roots check is an add-on: a registry lookup failure drops it, not the whole run.
        val componentsByProject =
            try {
                registryRootsByProject(knownProjectIds)
            } catch (e: Exception) {
                log.error(e) { "TC validation: registry roots lookup failed; skipping the VCS roots check" }
                emptyMap()
            }

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
                val rootsFinding = rootsFinding(componentsByProject[projectId].orEmpty())
                val issues =
                    (validators.validate(project) + listOfNotNull(rootsFinding))
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

    private fun removeStaleProjects(knownProjectIds: Set<String>): Int {
        val removed = teamcityValidationRepository.findDistinctStoredProjectIds().toSet() - knownProjectIds
        if (removed.isNotEmpty()) {
            transactionTemplate.executeWithoutResult { teamcityValidationRepository.deleteByProjectIdIn(removed) }
        }
        return removed.size
    }

    /** A live component's BASE-row VCS paths and every TeamCity project it is linked to. */
    private class ComponentRoots(
        val key: String,
        val paths: List<String>,
        val projectIds: Set<String>,
    )

    /** Project id -> the live components linked to it; the unit of comparison is the component, as in the placement Diff. */
    private fun registryRootsByProject(projectIds: Set<String>): Map<String, List<ComponentRoots>> {
        val pathsByComponent =
            componentConfigurationRepository
                .findAllRowsWithVcsEntries()
                .filter { !it.component.archived && it.overriddenAttribute == null }
                .groupBy({ it.component.id }, { row -> row.vcsEntries.map { it.vcsPath } })
                .mapValues { it.value.flatten() }
        val lines = versionLineRepository.findByProjectIdsWithComponent(projectIds)
        val projectsByComponent = lines.groupBy({ it.component.id }, { it.teamcityProject.projectId }).mapValues { it.value.toSet() }
        return lines
            .distinctBy { it.component.id to it.teamcityProject.projectId }
            .filter { pathsByComponent[it.component.id].orEmpty().isNotEmpty() }
            .groupBy(
                { it.teamcityProject.projectId },
                {
                    ComponentRoots(
                        it.component.componentKey,
                        pathsByComponent.getValue(it.component.id),
                        projectsByComponent.getValue(it.component.id),
                    )
                },
            )
    }

    /**
     * One merged finding per project: each component compared against the compile configurations of ALL its
     * linked projects (same unit as the Diff). A component whose other project cannot be read is skipped,
     * never reported — a partial view would show false missing roots.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun rootsFinding(components: List<ComponentRoots>): org.octopusden.octopus.validation.core.ValidationResult? =
        VcsRootsValidation.merge(
            components.mapNotNull { c ->
                val configs =
                    c.projectIds.flatMap { id ->
                        val external = try {
                            fetcher.fetch(id)
                        } catch (e: Exception) {
                            null
                        } ?: return@mapNotNull null
                        compileConfigsOf(external.buildTypes?.buildTypes.orEmpty())
                    }
                VcsRootsValidation.check(c.key, c.paths, configs)
            },
        )

    private fun VersionLineRepository.findDistinctLinkedProjectIdsSafely(): Set<String> =
        findDistinctLinkedProjectIds().filter { it.isNotBlank() }.toSet()
}
