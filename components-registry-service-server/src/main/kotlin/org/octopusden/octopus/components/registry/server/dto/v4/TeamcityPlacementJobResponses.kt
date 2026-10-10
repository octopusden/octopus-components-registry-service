package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
import java.time.Instant

/**
 * Wire shape for `POST /admin/teamcity-placement/diff` and `GET /admin/teamcity-placement/diff/job`.
 * `result` (the full row-by-row diff) is populated only once COMPLETED; the SPA's table reads it
 * from here or from the dedicated report endpoints (`.../diff/report.{json,html,csv}`), which serve
 * the latest COMPLETED run (a newer RUNNING or FAILED run does not replace it there).
 */
data class TeamcityPlacementDiffJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val rowCount: Int?,
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcityPlacementDiffJobState): TeamcityPlacementDiffJobResponse =
            TeamcityPlacementDiffJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                rowCount = state.result?.rows?.size,
            )
    }
}

/** Wire shape for `POST /admin/teamcity-placement/sync` and `GET /admin/teamcity-placement/sync/job`. */
data class TeamcityPlacementSyncJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val result: PlacementSyncResult?,
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcityPlacementSyncJobState): TeamcityPlacementSyncJobResponse =
            TeamcityPlacementSyncJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                result = state.result,
            )
    }
}

/** POST body for `/admin/teamcity-placement/sync` — the Portal offers "select all resolved" from
 * the last Diff's report. `diffId` is the id of the Diff run the selection came from (ADR-002
 * decision 1): the controller refuses the whole request with 409 when it no longer matches the
 * latest Diff's id, since Diff keeps no history and a replaced result must never be acted on. */
data class TeamcityPlacementSyncRequest(
    val diffId: String,
    val componentIds: List<java.util.UUID>,
)
