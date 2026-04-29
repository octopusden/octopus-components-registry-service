package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
import java.time.Instant

/**
 * Wire shape for `POST /admin/migrate` and `GET /admin/migrate/job`.
 *
 * `result` is populated only after the job reaches [JobState.COMPLETED]. While
 * RUNNING, the SPA leans on `currentComponent` + the `migrated/total/...`
 * counters to render a progress bar; on COMPLETED it switches to rendering
 * the full result tiles + failure list.
 */
data class MigrationJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val total: Int,
    val migrated: Int,
    val failed: Int,
    val skipped: Int,
    val currentComponent: String?,
    val errorMessage: String?,
    val result: FullMigrationResult?,
) {
    companion object {
        fun from(state: MigrationJobState): MigrationJobResponse =
            MigrationJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                total = state.total,
                migrated = state.migrated,
                failed = state.failed,
                skipped = state.skipped,
                currentComponent = state.currentComponent,
                errorMessage = state.errorMessage,
                result = state.result,
            )
    }
}
