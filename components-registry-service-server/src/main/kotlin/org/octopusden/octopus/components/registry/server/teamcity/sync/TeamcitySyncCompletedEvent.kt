package org.octopusden.octopus.components.registry.server.teamcity.sync

/** Fired by the sync job after a COMPLETED pass (post-commit, post-gate-release) to trigger validation. */
data class TeamcitySyncCompletedEvent(
    val jobId: String,
    val triggeredBy: String,
)
