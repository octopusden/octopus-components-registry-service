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
 * `skipped: <lowercase status>` (not resolved, or resolved-but-manual), `failed: <message>`. */
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

data class PlacementSyncResult(
    val triggeredBy: String,
    val requested: Int,
    val applied: Int,
    val skipped: Int,
    val failed: Int,
    val components: List<PlacementComponentSyncOutcome>,
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
 * place that decides overwrite safety; this service does not re-check it. Marker (per-range
 * `vcs.settings`) rows are report-only in this version (ADR-002 decisions 4/8): whatever their
 * status, they are always reported "skipped: report-only (per-range row)", never written.
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
                val outcome = if (freshRow != null) {
                    applyOrSkip(componentId, freshRow, snapshotByRow[rowId])
                } else {
                    "skipped: changed since diff"
                }
                when {
                    outcome == "applied" -> applied++
                    outcome.startsWith("failed") -> failed++
                    else -> skipped++
                }
                rowOutcomes += PlacementRowSyncOutcome(rowId, rowLabel, outcome)
            }
            val componentKey = freshRowsById.values.firstOrNull()?.componentKey
                ?: snapshotRowsForComponent.firstOrNull()?.componentKey
                ?: componentId.toString()
            components += PlacementComponentSyncOutcome(componentId, componentKey, rowOutcomes)
        }

        log.info { "TeamCity placement sync: requested=${componentIds.size}, applied=$applied, skipped=$skipped, failed=$failed" }
        return PlacementSyncResult(triggeredBy, componentIds.size, applied, skipped, failed, components)
    }

    private fun applyOrSkip(
        componentId: UUID,
        freshRow: PlacementRowDiff,
        snapshotRow: PlacementRowDiff?,
    ): String {
        // ADR-002 decisions 4/8 (owner review finding 5): marker (per-range `vcs.settings`) rows
        // are report-only in this version -- Diff shows and classifies them, but Sync never
        // writes one, whatever its status or selection. Checked first, ahead of every other
        // outcome, so a marker row is never reported "changed since diff" or a validation status
        // either -- it is always, unconditionally, report-only.
        if (freshRow.rowLabel != BASE_ROW_LABEL) return "skipped: report-only (per-range row)"
        if (snapshotRow == null || snapshotRow != freshRow) return "skipped: changed since diff"
        if (freshRow.status != PlacementDiffRowStatus.RESOLVED) return "skipped: ${freshRow.status.name.lowercase()}"
        return try {
            applyRow(componentId, freshRow)
            "applied"
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn(e) {
                "TeamCity placement sync: failed to write row ${freshRow.configurationRowId} of component '${freshRow.componentKey}'"
            }
            "failed: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Always a BASE row here — a marker (per-range `vcs.settings`) row never reaches this point;
     * see the report-only check in [applyOrSkip]. */
    private fun applyRow(
        componentId: UUID,
        row: PlacementRowDiff,
    ) {
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
                changeComment = "sync from TeamCity",
            ),
        )
    }

    private companion object {
        const val BASE_ROW_LABEL = "BASE"
    }
}
