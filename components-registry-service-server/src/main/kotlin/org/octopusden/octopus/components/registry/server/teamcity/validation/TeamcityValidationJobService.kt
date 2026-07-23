package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationResult
import java.time.Instant

/**
 * Server-side state of a TeamCity validation run. Mirrors `TeamcitySyncJobState`. Single-pod,
 * in-memory; a pod restart drops it (validation is not resumable — restart from scratch).
 */
data class TeamcityValidationJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    /** Populated only on [JobState.COMPLETED]. */
    val result: TeamcityValidationResult?,
    val errorMessage: String?,
)

/** Outcome of [TeamcityValidationJobService.startAsync]. */
data class StartTeamcityValidationResult(
    val state: TeamcityValidationJobState,
    /** `true` if this call started a new job; `false` if a RUNNING job was already active (attach). */
    val isNewlyStarted: Boolean,
)

/**
 * Async wrapper around [TeamcityValidationService.validate]. Mirrors `TeamcitySyncJobService`:
 * gate-serialized (only one of the migration/TC jobs runs at a time) and single-flight (a second
 * start while one is RUNNING attaches to it rather than spawning a duplicate).
 */
interface TeamcityValidationJobService {
    fun startAsync(triggeredBy: String): StartTeamcityValidationResult

    fun current(): TeamcityValidationJobState?
}
