package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobState
import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant

/**
 * Wire shape for `POST /admin/migrate-history` and `GET /admin/migrate-history/job`.
 *
 * Mirrors [MigrationJobResponse] for the components flow. `result` is populated
 * only after the job reaches [JobState.COMPLETED]; while RUNNING the SPA leans
 * on `processedCommits/totalCommits` + `currentSha` for progress rendering.
 */
data class HistoryMigrationJobResponse(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val totalCommits: Int,
    val processedCommits: Int,
    val auditRecords: Int,
    val skippedNoGroovy: Int,
    val skippedParseError: Int,
    val skippedUnknownNames: Int,
    val currentSha: String?,
    val targetRef: String?,
    val errorMessage: String?,
    val result: HistoryImportResult?,
    /**
     * SPA action hint: 'RETRY' | 'FORCE_RESET' | null. Replaces the
     * previous "match `errorMessage.includes('marked IN_PROGRESS')`"
     * substring contract — the new field is a positive discriminator,
     * stable across wording changes.
     */
    val recoveryAction: String?,
    /**
     * Discriminator for unambiguous 409 branching on the SPA. Always
     * `"job"` for this shape; the cross-kind 409 envelope sets
     * `"conflict"`. Without an explicit discriminator the SPA had to
     * branch on the *absence* of fields, which silently corrupts on
     * future contract drift.
     */
    val kind: String = "job",
) {
    companion object {
        fun from(state: HistoryMigrationJobState): HistoryMigrationJobResponse =
            HistoryMigrationJobResponse(
                id = state.id,
                state = state.state,
                startedAt = state.startedAt,
                finishedAt = state.finishedAt,
                totalCommits = state.totalCommits,
                processedCommits = state.processedCommits,
                auditRecords = state.auditRecords,
                skippedNoGroovy = state.skippedNoGroovy,
                skippedParseError = state.skippedParseError,
                skippedUnknownNames = state.skippedUnknownNames,
                currentSha = state.currentSha,
                targetRef = state.targetRef,
                errorMessage = state.errorMessage,
                result = state.result,
                recoveryAction = state.recoveryAction?.name,
            )
    }
}
