package org.octopusden.octopus.components.registry.server.dto.v4

/** Dashboard aggregates; `byType`/`byStatus` are distinct-component counts. */
data class TeamcityValidationSummaryResponse(
    val componentsWithIssues: Int,
    val findings: Int,
    val byType: Map<String, Int>,
    val byStatus: Map<String, Int>,
)
