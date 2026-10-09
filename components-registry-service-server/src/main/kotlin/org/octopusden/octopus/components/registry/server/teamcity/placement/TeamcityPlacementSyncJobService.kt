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
 * is the exact Diff result the request's `diffId` is validated against (owner review finding 1
 * hardening): the caller reads [TeamcityPlacementDiffJobService.lastCompleted] ONCE, stamps its id
 * into the result's `diffId`, and passes that SAME result object through here — never re-fetched
 * once the job actually runs. A re-fetch would reopen the TOCTOU window the `diffId` check exists
 * to close: a new Diff completing between the check and the (async) work running would otherwise
 * let Sync silently act on a result the caller never validated.
 *
 * Order of answers (PR #510 review): a cross-kind gate conflict (including a RUNNING Diff) throws
 * [org.octopusden.octopus.components.registry.server.service.MigrationConflictException]; a RUNNING
 * Sync is returned as an attach (`isNewlyStarted = false`); only then is [requestedDiffId] compared
 * with [latestDiff]'s `diffId`, throwing [PlacementDiffStaleException] on a mismatch or when no
 * Diff has completed ([latestDiff] `null`).
 */
interface TeamcityPlacementSyncJobService {
    fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        requestedDiffId: String,
        latestDiff: PlacementDiffResult?,
    ): StartPlacementSyncResult

    fun current(): TeamcityPlacementSyncJobState?
}
