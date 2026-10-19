package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant

data class TeamcityPlacementDiffJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val result: PlacementDiffResult?,
    val errorMessage: String?,
)

data class StartPlacementDiffResult(
    val state: TeamcityPlacementDiffJobState,
    val isNewlyStarted: Boolean,
)

/**
 * ONB-002: async wrapper around [TeamcityPlacementDiffService.runDiff], mirroring
 * `TeamcitySyncJobService` — same 202-then-poll shape, same cross-kind gate
 * ([org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind.TC_PLACEMENT_DIFF]),
 * no DB-backed resumability (a pod restart starts the next run from scratch; the result of the
 * last run in THIS pod is all [current] and [lastCompleted] ever return).
 */
interface TeamcityPlacementDiffJobService {
    fun startAsync(triggeredBy: String): StartPlacementDiffResult

    fun current(): TeamcityPlacementDiffJobState?

    /**
     * The latest run that COMPLETED, independent of [current]: a RUNNING or FAILED newer run does
     * not replace it. The reports and Sync's `diffId` check read this one, so the Portal table
     * stays populated while a Diff runs (or after one fails). `null` until a Diff has completed in
     * this pod.
     */
    fun lastCompleted(): TeamcityPlacementDiffJobState?
}
