package org.octopusden.octopus.components.registry.server.teamcity.validation

/** Summary of one validation pass (for the job state / event journal). */
data class TeamcityValidationResult(
    val scanned: Int,
    val succeeded: Int,
    val failed: Int,
    val projectsWithIssues: Int,
    val removed: Int,
    val errors: List<String>,
)
