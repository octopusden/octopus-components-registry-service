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
 * ONB-002: async wrapper around [TeamcityPlacementSyncService.sync]. [startAsync] reads
 * [latestDiff] ONCE, under the gate, checks its `diffId` against [requestedDiffId] (throwing
 * [PlacementDiffStaleException] on a mismatch or when it is `null`) and runs the job on that same
 * result; re-reading it later would reopen the race the check closes. The 409 answers are listed
 * on `TeamcityPlacementControllerV4.startSync`.
 */
interface TeamcityPlacementSyncJobService {
    fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        requestedDiffId: String,
        latestDiff: () -> PlacementDiffResult?,
    ): StartPlacementSyncResult

    fun current(): TeamcityPlacementSyncJobState?
}
