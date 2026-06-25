package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Registry-wide health statistics for the Portal admin "Registry Health" page (SYS-057).
 *
 * Counts + people only. Every figure is produced by a SQL aggregation (COUNT / GROUP BY)
 * over the regular (non-FAKE-aggregator) component set — the components never load into
 * memory — so the totals line up with the v4 component list.
 *
 * The `componentsBy*` maps key a username to the number of components on which that user
 * holds the role. A user who is both a release manager and a security champion appears
 * (with that role's count) in BOTH maps independently.
 *
 * Deliberately carries NO problem/validation dimension: validation problems are owned by
 * the Portal backend, not CRS. The shape leaves room for a future problem breakdown to be
 * added by its own owner (a separate response/endpoint) without colliding here.
 */
data class HealthStatisticsResponse(
    val totalComponents: Long,
    val activeComponents: Long,
    val componentsByOwner: Map<String, Long>,
    val componentsByReleaseManager: Map<String, Long>,
    val componentsBySecurityChampion: Map<String, Long>,
)
