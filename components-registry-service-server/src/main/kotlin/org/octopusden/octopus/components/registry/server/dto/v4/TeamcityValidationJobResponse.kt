package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobState
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationResult
import java.time.Instant

/**
 * Wire shape for `POST /admin/teamcity-validation` and `GET /admin/teamcity-validation/job`.
 * Mirrors `TeamcitySyncJobResponse` (`kind = "job"` discriminator) so the SPA's same-kind-attach
 * 409 branching works identically. `result` is populated only once COMPLETED.
 */
data class TeamcityValidationJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val errorMessage: String?,
    val result: TeamcityValidationResult?,
    val kind: String = "job",
) {
    companion object {
        fun from(state: TeamcityValidationJobState): TeamcityValidationJobResponse =
            TeamcityValidationJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                errorMessage = state.errorMessage,
                result = state.result,
            )
    }
}
