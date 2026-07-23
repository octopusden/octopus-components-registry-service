package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncResult
import java.time.Instant

/**
 * Wire shape for `POST /admin/teamcity-project-ids/sync` and
 * `GET /admin/teamcity-project-ids/sync/job`.
 *
 * `result` is populated only after the job reaches [JobState.COMPLETED]. While
 * RUNNING the SPA renders an indeterminate spinner; on COMPLETED it switches
 * to rendering the per-pass counter tiles + the first error from the result
 * payload.
 *
 * The `kind = "job"` discriminator mirrors [MigrationJobResponse] so the SPA's
 * `parseSameKindAttach` 409-branching helper works the same way for TC as for
 * components / history.
 */
data class TeamcitySyncJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val result: TeamcitySyncResult?,
    /** Discriminator for unambiguous 409 branching on the SPA. Always `"job"` for this shape. */
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcitySyncJobState): TeamcitySyncJobResponse =
            TeamcitySyncJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                result = state.result,
            )
    }
}
