package org.octopusden.octopus.components.registry.server.teamcity.placement

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.springframework.stereotype.Service
import java.util.UUID

/** One row's write outcome. `outcome` is one of: `applied`, `skipped: changed since diff`,
 * `skipped: outside scope` (a marker row, or an archived component's row), `skipped: <lowercase
 * status>` (not resolved, or resolved-but-manual/invalid), `failed: <message>`. */
data class PlacementRowSyncOutcome(
    val configurationRowId: UUID,
    val rowLabel: String,
    val outcome: String,
)

data class PlacementComponentSyncOutcome(
    val componentId: UUID,
    val componentKey: String,
    val rows: List<PlacementRowSyncOutcome>,
)

/** One written field's rollback trace (owner review finding 6): [root] is the affected VCS entry's
 * name for `checkoutDirectory` / `sourcePath`, or `""` for the row-level `buildWorkingDirectory`. */
data class PlacementFieldChange(
    val componentKey: String,
    val rowLabel: String,
    val root: String,
    val field: String,
    val before: String?,
    val after: String?,
)

data class PlacementSyncResult(
    val triggeredBy: String,
    val requested: Int,
    val applied: Int,
    val skipped: Int,
    val failed: Int,
    val components: List<PlacementComponentSyncOutcome>,
    val fieldChanges: List<PlacementFieldChange> = emptyList(),
)

/**
 * ONB-002: applies a previously-run Diff's RESOLVED rows for the selected components.
 *
 * Safety (design brief): re-reads TeamCity and the registry and re-derives before writing; if the
 * fresh derivation of a row differs from the snapshot it was selected from, that row is skipped
 * with "changed since diff" rather than applied blind. Every write goes through
 * [ComponentManagementService] — the same validation, name-derivation and audit path a human PATCH
 * uses — never direct SQL. MANUAL_EDIT rows are never selected for write in the first place (they
 * are not RESOLVED), so [TeamcityPlacementDiffService.runDiff]'s own re-derivation is the single
 * place that decides overwrite safety; this service does not re-check it. A marker (per-range
 * `vcs.settings`) row, or a row of an archived component, is always `OUTSIDE_SCOPE` from Diff
 * (ADR-002 decisions 4/8; spec-conformance finding 1) and always reported "skipped: outside
 * scope", never written.
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcityPlacementSyncService(
    private val diffService: TeamcityPlacementDiffService,
    private val componentManagementService: ComponentManagementService,
) {
    private val log = KotlinLogging.logger {}

    fun sync(
        componentIds: Set<UUID>,
        latestDiff: PlacementDiffResult?,
        jobId: String,
        triggeredBy: String,
    ): PlacementSyncResult {
        val snapshotByRow = latestDiff?.rows.orEmpty().associateBy { it.configurationRowId }
        // Force a live TeamCity read for this safety re-derivation — see invalidateTeamcityCache's
        // kdoc for why a cache-served "fresh" derivation would defeat "changed since diff".
        val fresh = if (componentIds.isEmpty()) {
            emptyList()
        } else {
            diffService.invalidateTeamcityCache()
            diffService.runDiff(componentIds).rows
        }
        val components = mutableListOf<PlacementComponentSyncOutcome>()
        val fieldChanges = mutableListOf<PlacementFieldChange>()
        var applied = 0
        var skipped = 0
        var failed = 0

        for (componentId in componentIds) {
            val freshRowsById = fresh.filter { it.componentId == componentId }.associateBy { it.configurationRowId }
            val snapshotRowsForComponent = snapshotByRow.values.filter { it.componentId == componentId }
            // The union of both sides: a snapshot row absent from the fresh re-derivation (out of
            // scope now, its component's TeamCity link gone, ...) is still reported — as "changed
            // since diff", same as one that re-derived differently — instead of silently vanishing
            // from the result with no outcome and no counter incremented at all.
            val rowIds = (freshRowsById.keys + snapshotRowsForComponent.map { it.configurationRowId }).distinct()
            val rowOutcomes = mutableListOf<PlacementRowSyncOutcome>()
            for (rowId in rowIds) {
                val freshRow = freshRowsById[rowId]
                val rowLabel = freshRow?.rowLabel ?: snapshotByRow[rowId]?.rowLabel ?: "unknown"
                val (outcome, changes) = if (freshRow != null) {
                    applyOrSkip(componentId, freshRow, snapshotByRow[rowId], jobId)
                } else {
                    "skipped: changed since diff" to emptyList()
                }
                when {
                    outcome == "applied" -> applied++
                    outcome.startsWith("failed") -> failed++
                    else -> skipped++
                }
                rowOutcomes += PlacementRowSyncOutcome(rowId, rowLabel, outcome)
                fieldChanges += changes
            }
            val componentKey = freshRowsById.values.firstOrNull()?.componentKey
                ?: snapshotRowsForComponent.firstOrNull()?.componentKey
                ?: componentId.toString()
            components += PlacementComponentSyncOutcome(componentId, componentKey, rowOutcomes)
        }

        log.info { "TeamCity placement sync: requested=${componentIds.size}, applied=$applied, skipped=$skipped, failed=$failed" }
        return PlacementSyncResult(triggeredBy, componentIds.size, applied, skipped, failed, components, fieldChanges)
    }

    private fun applyOrSkip(
        componentId: UUID,
        freshRow: PlacementRowDiff,
        snapshotRow: PlacementRowDiff?,
        jobId: String,
    ): Pair<String, List<PlacementFieldChange>> {
        // ADR-002 decisions 4/8 (owner review finding 5) + spec-conformance finding 1: a marker
        // (per-range `vcs.settings`) row, or a row of an archived component, is always
        // OUTSIDE_SCOPE from Diff -- Sync never writes either, whatever the selection. Checked
        // first, ahead of every other outcome, so such a row is never reported "changed since
        // diff" either -- it is always, unconditionally, out of scope.
        if (freshRow.status == PlacementDiffRowStatus.OUTSIDE_SCOPE) return "skipped: outside scope" to emptyList()
        if (snapshotRow == null || snapshotRow != freshRow) return "skipped: changed since diff" to emptyList()
        if (freshRow.status != PlacementDiffRowStatus.RESOLVED) return "skipped: ${freshRow.status.name.lowercase()}" to emptyList()
        return try {
            "applied" to applyRow(componentId, freshRow, jobId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn(e) {
                "TeamCity placement sync: failed to write row ${freshRow.configurationRowId} of component '${freshRow.componentKey}'"
            }
            "failed: ${e.message ?: e::class.simpleName}" to emptyList()
        }
    }

    /** Always a BASE row here — a marker (per-range `vcs.settings`) row never reaches this point;
     * see the report-only check in [applyOrSkip]. Returns the field-level before/after trace (owner
     * review finding 6) for every field that actually changed. */
    private fun applyRow(
        componentId: UUID,
        row: PlacementRowDiff,
        jobId: String,
    ): List<PlacementFieldChange> {
        val vcsEntries = row.entries.map {
            VcsEntryRequest(
                vcsPath = it.vcsPath,
                branch = it.branch,
                tag = it.tag,
                hotfixBranch = it.hotfixBranch,
                repositoryType = it.repositoryType,
                sourcePath = it.derivedSourcePath,
                checkoutDirectory = it.derivedCheckoutDirectory,
            )
        }
        val current = componentManagementService.getComponent(componentId)
        componentManagementService.updateComponent(
            componentId,
            ComponentUpdateRequest(
                version = current.version,
                baseConfiguration = BaseConfigurationRequest(
                    vcsEntries = vcsEntries,
                    // Base-row tri-state: null=unchanged, ""=clear to the checkout root — this
                    // write always sets the fully-resolved value, so root is "" here, never null.
                    buildWorkingDirectory = row.derivedBuildWorkingDirectory ?: "",
                ),
                // The fixed provenance tag PlacementEditHistory keys on, plus THIS Sync run's own
                // id — so an audit row it writes can be told apart from any other Sync run's, and
                // a rollback (technical-design.md §6.8) can select exactly this run's writes.
                changeComment = "${PlacementEditHistory.SYNC_CHANGE_COMMENT_PREFIX} (job $jobId)",
            ),
        )
        return row.entries.flatMap { e ->
            listOfNotNull(
                if (e.derivedCheckoutDirectory != e.currentCheckoutDirectory) {
                    PlacementFieldChange(
                        row.componentKey,
                        row.rowLabel,
                        e.name,
                        "checkoutDirectory",
                        e.currentCheckoutDirectory,
                        e.derivedCheckoutDirectory,
                    )
                } else {
                    null
                },
                if (e.derivedSourcePath != e.currentSourcePath) {
                    PlacementFieldChange(row.componentKey, row.rowLabel, e.name, "sourcePath", e.currentSourcePath, e.derivedSourcePath)
                } else {
                    null
                },
            )
        } + listOfNotNull(
            if (row.derivedBuildWorkingDirectory != row.currentBuildWorkingDirectory) {
                PlacementFieldChange(
                    row.componentKey,
                    row.rowLabel,
                    "",
                    "buildWorkingDirectory",
                    row.currentBuildWorkingDirectory,
                    row.derivedBuildWorkingDirectory,
                )
            } else {
                null
            },
        )
    }
}
