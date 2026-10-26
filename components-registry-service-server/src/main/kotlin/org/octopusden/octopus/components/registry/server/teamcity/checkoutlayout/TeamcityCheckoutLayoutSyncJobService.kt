package org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout

import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant
import java.util.UUID

data class TeamcityCheckoutLayoutSyncJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val result: CheckoutLayoutSyncResult?,
    val errorMessage: String?,
)

data class StartCheckoutLayoutSyncResult(
    val state: TeamcityCheckoutLayoutSyncJobState,
    val isNewlyStarted: Boolean,
)

/**
 * ONB-002: async wrapper around [TeamcityCheckoutLayoutSyncService.sync]. [startAsync] reads
 * [latestDiff] ONCE, under the gate, checks its `diffId` against [requestedDiffId] (throwing
 * [CheckoutLayoutDiffStaleException] on a mismatch or when it is `null`) and runs the job on that same
 * result; re-reading it later would reopen the race the check closes. The 409 answers are listed
 * on `TeamcityCheckoutLayoutControllerV4.startSync`.
 */
interface TeamcityCheckoutLayoutSyncJobService {
    fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        requestedDiffId: String,
        latestDiff: () -> CheckoutLayoutDiffResult?,
    ): StartCheckoutLayoutSyncResult

    fun current(): TeamcityCheckoutLayoutSyncJobState?
}
