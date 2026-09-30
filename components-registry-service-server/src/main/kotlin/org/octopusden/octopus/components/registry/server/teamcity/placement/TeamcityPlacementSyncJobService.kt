package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant
import java.util.UUID

data class TeamcityPlacementSyncJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val result: PlacementSyncResult?,
    val errorMessage: String?,
)

data class StartPlacementSyncResult(
    val state: TeamcityPlacementSyncJobState,
    val isNewlyStarted: Boolean,
)

/**
 * ONB-002: async wrapper around [TeamcityPlacementSyncService.sync], run against the LATEST
 * completed [TeamcityPlacementDiffJobService] result at the moment this job's work actually runs
 * (not at request time) — same in-memory, single-pod, non-resumable shape as the Diff job.
 */
interface TeamcityPlacementSyncJobService {
    fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
    ): StartPlacementSyncResult

    fun current(): TeamcityPlacementSyncJobState?
}
