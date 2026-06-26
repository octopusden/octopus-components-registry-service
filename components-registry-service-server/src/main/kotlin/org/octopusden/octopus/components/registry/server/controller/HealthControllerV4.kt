package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.HealthStatisticsResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.NameCountRow
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Registry-wide health statistics (SYS-057) for the Portal admin "Registry Health" page.
 *
 * Counts + people only — every figure is computed in SQL (COUNT / GROUP BY) so the
 * components never load into memory. Problem/validation aggregation is intentionally
 * absent: it is owned by the Portal backend, not CRS.
 *
 * Gated by `ACCESS_COMPONENTS`, the same read permission every other v4 list/read endpoint
 * uses. The Portal hides the page from non-admins as a nav-visibility concern; the figures
 * expose only aggregates over data already readable via the component list, so no new CRS
 * permission is invented.
 *
 * Same `@ConditionalOnDatabaseEnabled` level as `ComponentControllerV4`: the aggregations
 * are JPA/SQL, so the endpoint only registers when the DB is enabled (the git-only `no-db`
 * boot mode has no JPA repositories).
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/health")
class HealthControllerV4(
    private val componentRepository: ComponentRepository,
) {
    @GetMapping("/statistics")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    @Transactional(readOnly = true)
    fun statistics(): HealthStatisticsResponse =
        HealthStatisticsResponse(
            totalComponents = componentRepository.countRegularComponents(),
            activeComponents = componentRepository.countRegularComponentsByArchived(false),
            componentsByOwner = componentRepository.countComponentsByOwner().toCountMap(),
            componentsByReleaseManager = componentRepository.countComponentsByReleaseManager().toCountMap(),
            componentsBySecurityChampion = componentRepository.countComponentsBySecurityChampion().toCountMap(),
        )

    private fun List<NameCountRow>.toCountMap(): Map<String, Long> = associate { it.name to it.count }
}
