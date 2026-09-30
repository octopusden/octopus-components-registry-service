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
 * ONB-002: async wrapper around [TeamcityPlacementSyncService.sync]. [startAsync]'s [latestDiff]
 * is the exact Diff result the caller validated its `diffId` against (owner review finding 1
 * hardening): the caller reads [TeamcityPlacementDiffJobService.current] ONCE, checks its id, and
 * passes the SAME result object through here — never re-fetched once the job actually runs. A
 * re-fetch would reopen the TOCTOU window the `diffId` check exists to close: a new Diff
 * completing between the check and the (async) work running would otherwise let Sync silently act
 * on a result the caller never validated.
 */
interface TeamcityPlacementSyncJobService {
    fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        latestDiff: PlacementDiffResult,
    ): StartPlacementSyncResult

    fun current(): TeamcityPlacementSyncJobState?
}
