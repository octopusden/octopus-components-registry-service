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
    /**
     * Sub-phase ("DEFAULTS" | "COMPONENTS"). Serialized as the enum name (a
     * String, not the enum itself) so client/server are not coupled through
     * Jackson's enum (de)serialization conventions. Older SPA builds simply
     * ignore this field; older CRS builds simply omit it from the JSON, and
     * the SPA falls back to a generic "Running…" label.
     */
    val phase: String?,
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
                phase = state.phase?.name,
            )
    }
}
