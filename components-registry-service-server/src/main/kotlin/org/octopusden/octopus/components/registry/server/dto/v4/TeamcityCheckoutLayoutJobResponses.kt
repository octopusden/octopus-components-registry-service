package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobState
import java.time.Instant

/**
 * Wire shape for `POST /admin/teamcity-checkout-layout/diff` and `GET /admin/teamcity-checkout-layout/diff/job`.
 * `result` (the full row-by-row diff) is populated only once COMPLETED; the SPA's table reads it
 * from here or from the dedicated report endpoints (`.../diff/report.{json,html,csv}`), which serve
 * the latest COMPLETED run (a newer RUNNING or FAILED run does not replace it there).
 */
data class TeamcityCheckoutLayoutDiffJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val rowCount: Int?,
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcityCheckoutLayoutDiffJobState): TeamcityCheckoutLayoutDiffJobResponse =
            TeamcityCheckoutLayoutDiffJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                rowCount = state.result?.rows?.size,
            )
    }
}

/** Wire shape for `POST /admin/teamcity-checkout-layout/sync` and `GET /admin/teamcity-checkout-layout/sync/job`. */
data class TeamcityCheckoutLayoutSyncJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val result: CheckoutLayoutSyncResult?,
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcityCheckoutLayoutSyncJobState): TeamcityCheckoutLayoutSyncJobResponse =
            TeamcityCheckoutLayoutSyncJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                result = state.result,
            )
    }
}

/** POST body for `/admin/teamcity-checkout-layout/sync` — the Portal offers "select all resolved" from
 * the last Diff's report. `diffId` is the id of the Diff run the selection came from (ADR-023
 * decision 1): the controller refuses the whole request with 409 when it no longer matches the
 * latest Diff's id, since Diff keeps no history and a replaced result must never be acted on. */
data class TeamcityCheckoutLayoutSyncRequest(
    val diffId: String,
    val componentIds: List<java.util.UUID>,
)
