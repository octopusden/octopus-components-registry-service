package org.octopusden.octopus.components.registry.server.teamcity.placement

import com.fasterxml.jackson.annotation.JsonIgnore
import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.validation.EnrichedTcProjectFetcher
import org.octopusden.octopus.components.registry.server.util.VcsPlacementValidator
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType as ExternalBuildType

/** Extends the pure engine's [PlacementRowStatus] with the two outcomes that need the registry's
 * current values and audit trail (not derivable from TeamCity data alone), plus a fetch failure. */
enum class PlacementDiffRowStatus {
    RESOLVED,
    CONFLICT,
    UNEXPRESSIBLE,
    NO_CHAIN,
    OUTSIDE_TEMPLATES,
    COMPILE_PAUSED,

    /** Derivation resolved, but at least one differing value was set by a real edit, not V8 — see [PlacementEditHistory]. */
    MANUAL_EDIT,

    /** Derivation resolved and matches every current value; nothing to do. */
    IN_SYNC,

    /** Derivation resolved and differs from the current value, but the derived values would fail
     * the SAME v4 write validation a human PATCH runs — see `util.VcsPlacementValidator`. Never
     * offered to Sync. The validation message is in the row's `notes`. */
    INVALID,

    /** The component's linked TeamCity project(s) could not be read; see the row's notes for why. */
    TC_ERROR,

    /** TeamCity's compile configurations attach VCS roots the registry does not list (e.g. a shared
     * tooling repository). Never offered to Sync; the details are in TeamCity Validation. */
    ROOTS_MISMATCH,

    ;

    companion object {
        fun from(engine: PlacementRowStatus): PlacementDiffRowStatus = valueOf(engine.name)
    }
}

data class PlacementEntryDiff(
    val name: String,
    val vcsPath: String,
    val branch: String?,
    val tag: String?,
    val hotfixBranch: String?,
    val repositoryType: String?,
    val currentCheckoutDirectory: String?,
    val currentSourcePath: String?,
    val derivedCheckoutDirectory: String?,
    val derivedSourcePath: String?,
)

data class PlacementRowDiff(
    val componentId: UUID,
    val componentKey: String,
    val configurationRowId: UUID,
    val versionRange: String,
    /** "BASE" or the marker name (`vcs.settings`) — [ComponentConfigurationEntity.rowType] / `overriddenAttribute`. */
    val rowLabel: String,
    val status: PlacementDiffRowStatus,
    val entries: List<PlacementEntryDiff>,
    val currentBuildWorkingDirectory: String?,
    val derivedBuildWorkingDirectory: String?,
    val sourceBuildTypeIds: List<String>,
    val notes: List<String>,
    /** The component's optimistic-lock version as read together with this row; Sync writes with it.
     * Internal only: not part of the report API. */
    @get:JsonIgnore
    val componentVersion: Long = 0,
)

data class PlacementDiffResult(
    val generatedAt: Instant,
    val rows: List<PlacementRowDiff>,
    /** The Diff run this result belongs to — what a Sync request must name. Set on the report endpoint. */
    val diffId: String? = null,
)

/**
 * ONB-002: the read-only TeamCity -> CRS VCS-placement Diff. Walks every component's
 * configuration rows that carry VCS entries, derives their placement from the linked project(s)'
 * compile build configurations ([derive]), and classifies each row against the registry's current
 * values. Only the current (BASE) configuration of a non-archived component is diffed (owner
 * decision after the first QA run): archived components and version-range (`vcs.settings` marker)
 * rows are left out entirely.
 *
 * Scope (design brief, BASE rows of a non-archived component only): multi-root rows; single-root
 * rows where TeamCity derives (or the registry already carries) a non-root Checkout Directory;
 * single-root rows with a non-default Build Working Directory (current or derived). A single-root
 * row with nothing on either side (no Checkout Directory, checkout-root Build Working Directory)
 * is out of scope entirely — ADR-001 keeps single-root Checkout Directories out of the
 * escrow-facing `main` name — and never appears
 * in the result, not even as "in sync". The exception: a `CONFLICT` or `UNEXPRESSIBLE` derivation
 * produces no values for the filter to judge, so such a row is always reported (like `TC_ERROR`).
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcityPlacementDiffService(
    private val componentConfigurationRepository: ComponentConfigurationRepository,
    private val versionLineRepository: VersionLineRepository,
    private val enrichedTcProjectFetcher: EnrichedTcProjectFetcher,
    private val placementEditHistory: PlacementEditHistory,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Forces the next [runDiff] to read live TeamCity state instead of [enrichedTcProjectFetcher]'s
     * short-TTL cache. `TeamcityPlacementSyncService` calls this before its safety re-derivation:
     * without it, a re-derivation running inside the cache's TTL window could compare against the
     * SAME cached (now stale) response the original Diff read, silently agreeing with a snapshot
     * that no longer reflects TeamCity — defeating the "changed since diff" check entirely. A plain
     * Diff run does not call this; its own staleness window is an accepted, documented tradeoff.
     */
    fun invalidateTeamcityCache() {
        enrichedTcProjectFetcher.invalidateAll()
    }

    /** [componentIds] restricts the walk to those components — used by Sync to re-derive just the
     * rows it is about to apply, against the same rules the original Diff used. */
    fun runDiff(componentIds: Set<UUID>? = null): PlacementDiffResult {
        val rows = componentConfigurationRepository
            .findAllRowsWithVcsEntries()
            .filter { componentIds == null || it.component.id in componentIds }
            .filter { !it.component.archived && it.overriddenAttribute == null }
        val byComponent = rows.groupBy { it.component }
        val result = byComponent.flatMap { (component, componentRows) -> diffComponent(component, componentRows) }
        log.info { "TeamCity placement diff: ${result.size} row(s) in scope across ${byComponent.size} linked component(s)" }
        return PlacementDiffResult(Instant.now(), result)
    }

    private fun diffComponent(
        component: ComponentEntity,
        componentRows: List<ComponentConfigurationEntity>,
    ): List<PlacementRowDiff> {
        val componentId = component.id ?: return emptyList()
        val projectIds = versionLineRepository.findDistinctTeamcityProjectIdsByComponentId(componentId).orEmpty()
        if (projectIds.isEmpty()) return emptyList() // no TeamCity link: nothing to diff against
        val chain = fetchChain(component, projectIds)
        return componentRows.mapNotNull { diffRow(component, componentId, it, chain) }
    }

    private fun diffRow(
        component: ComponentEntity,
        componentId: UUID,
        row: ComponentConfigurationEntity,
        chain: ChainOutcome,
    ): PlacementRowDiff? {
        val entries = row.vcsEntries.sortedBy { it.sortOrder }
        if (entries.isEmpty()) return null
        val placementEntries = entries.map {
            PlacementRegistryEntry(it.name, it.vcsPath, it.repositoryType, it.sourcePath, it.checkoutDirectory)
        }
        val rowLabel = BASE_ROW_LABEL

        if (chain is ChainOutcome.Error) {
            // No scope filter: without the chain nothing is derived, so a dropped row would hide the error.
            return toRowDiff(
                component,
                componentId,
                row,
                rowLabel,
                entries,
                PlacementDiffRowStatus.TC_ERROR,
                emptyMap(),
                null,
                listOf(chain.message),
                emptyList(),
            )
        }
        chain as ChainOutcome.Ok
        val mismatch = rootsMismatchNote(placementEntries, chain)
        if (mismatch != null) {
            return toRowDiff(
                component,
                componentId,
                row,
                rowLabel,
                entries,
                PlacementDiffRowStatus.ROOTS_MISMATCH,
                emptyMap(),
                null,
                listOf(mismatch),
                chain.sourceBuildTypeIds,
            )
        }
        val repoKeys = placementEntries.map { repoKey(it.vcsPath) }.toSet()
        val outsideCounts = chain.nonCompileRuled.filterValues { it.first.any { key -> key in repoKeys } }.mapValues { it.value.second }
        val derivation = derive(DeriveInput(placementEntries, chain.compileConfigs, chain.pausedCompileCount, outsideCounts))

        val derivedForScope = derivation.perEntry.values.firstOrNull()
        val inScope = inScope(
            entries,
            row.buildWorkingDirectory,
            derivedForScope?.checkoutDirectory,
            derivedForScope?.sourcePath,
            derivation.buildWorkingDirectory,
        )
        // CONFLICT / UNEXPRESSIBLE derive no values, so the filter can't judge them -- like TC_ERROR
        // and ROOTS_MISMATCH they are always reported, never dropped.
        val alwaysReported = derivation.status == PlacementRowStatus.CONFLICT || derivation.status == PlacementRowStatus.UNEXPRESSIBLE
        if (!inScope && !alwaysReported) return null

        val (status, extraNotes) = finalizeStatus(componentId, placementEntries, entries, row.buildWorkingDirectory, derivation)
        return toRowDiff(
            component,
            componentId,
            row,
            rowLabel,
            entries,
            status,
            derivation.perEntry,
            derivation.buildWorkingDirectory,
            extraNotes + derivation.notes,
            chain.sourceBuildTypeIds,
        )
    }

    /** Same comparison TeamCity Validation reports ([compareVcsRoots]); only extra roots decide the status here. */
    private fun rootsMismatchNote(
        entries: List<PlacementRegistryEntry>,
        chain: ChainOutcome.Ok,
    ): String? {
        val extra = compareVcsRoots(entries.map { it.vcsPath }, chain.compileConfigs).extra
        if (extra.isEmpty()) return null
        val attached = extra.joinToString(", ") { "${it.repo} (${it.buildTypeIds.joinToString(", ")})" }
        return "VCS roots differ from the registry: TeamCity also attaches $attached — see TeamCity Validation"
    }

    /**
     * Scope filter — see class kdoc. Only single-root rows are filtered; a multi-root row is
     * always in scope. Checks Source Path alongside Checkout Directory / Build Working Directory:
     * ADR-001 allows a single root with an empty Checkout Directory but a non-empty Source Path
     * (`+:<Source Path> => <Source Path>`, a monorepo subdirectory with no rename) — that is a real
     * placement a row could need synced or reported on, not "nothing".
     */
    private fun inScope(
        entries: List<VcsSettingsEntryEntity>,
        currentBwd: String?,
        derivedCd: String?,
        derivedSp: String?,
        derivedBwd: String?,
    ): Boolean {
        if (entries.size != 1) return true
        val entry = entries.single()
        return entry.checkoutDirectory != null ||
            entry.sourcePath != null ||
            currentBwd != null ||
            derivedCd != null ||
            derivedSp != null ||
            derivedBwd != null
    }

    /**
     * Layers INVALID / MANUAL_EDIT / IN_SYNC over a RESOLVED derivation by comparing it against the
     * row's current values; passes every other status straight through.
     */
    private fun finalizeStatus(
        componentId: UUID,
        entries: List<PlacementRegistryEntry>,
        rawEntries: List<VcsSettingsEntryEntity>,
        currentBwd: String?,
        derivation: PlacementDerivation,
    ): Pair<PlacementDiffRowStatus, List<String>> {
        if (derivation.status != PlacementRowStatus.RESOLVED) {
            return PlacementDiffRowStatus.from(derivation.status) to emptyList()
        }
        val differingEntries = entries.indices.filter { i ->
            val derived = derivation.perEntry[i]
            derived?.checkoutDirectory != entries[i].currentCheckoutDirectory || derived?.sourcePath != entries[i].currentSourcePath
        }
        val bwdDiffers = derivation.buildWorkingDirectory != currentBwd
        if (differingEntries.isEmpty() && !bwdDiffers) {
            return PlacementDiffRowStatus.IN_SYNC to emptyList()
        }
        // Owner review finding 4 (ADR-002 decision 3): RESOLVED requires the derived values to
        // ALSO pass the same validation a real v4 write runs. This is the ONLY place that runs it
        // (spec-conformance finding 3): PlacementRules.derive's pure engine no longer pre-empts it
        // with its own narrower check — UNEXPRESSIBLE there is reserved for a rule/WORK_DIR shape
        // that can't be parsed at all; a value that parses fine but fails a CRS validation rule
        // (root uniqueness, reserved/duplicate name, Source Path shape, BWD rules) surfaces as
        // RESOLVED from derive() and is downgraded to INVALID right here instead. Names are derived
        // by the SAME rule the real write uses
        // (VcsPlacementValidator.deriveNames — owner review finding 4 hardening): building the
        // candidate as `derived.checkoutDirectory ?: e.name`, with no exclusion/fallback, could flag
        // a row INVALID that a real write would accept (an unplaced entry's kept name colliding with
        // a new Checkout Directory falls back to "main", not a validation failure).
        val invalidMessage = runCatching {
            val checkoutDirectories = rawEntries.indices.map { i -> derivation.perEntry[i]?.checkoutDirectory }
            val names = VcsPlacementValidator.deriveNames(
                rawEntries,
                rawEntries.indices.map { i -> Triple(rawEntries[i].vcsPath, rawEntries[i].repositoryType, checkoutDirectories[i]) },
            )
            val candidateEntries = rawEntries.mapIndexed { i, e ->
                VcsSettingsEntryEntity(
                    componentConfiguration = e.componentConfiguration,
                    name = names[i],
                    vcsPath = e.vcsPath,
                    branch = e.branch,
                    tag = e.tag,
                    hotfixBranch = e.hotfixBranch,
                    repositoryType = e.repositoryType,
                    sortOrder = i,
                    sourcePath = derivation.perEntry[i]?.sourcePath,
                    checkoutDirectory = checkoutDirectories[i],
                )
            }
            VcsPlacementValidator.validateVcsPlacement(candidateEntries)
            VcsPlacementValidator.validateBuildWorkingDirectory(candidateEntries, derivation.buildWorkingDirectory)
        }.exceptionOrNull()
        if (invalidMessage != null) {
            return PlacementDiffRowStatus.INVALID to listOf(invalidMessage.message ?: "invalid placement")
        }
        // Owner review finding 2 hardening: checked per FIELD, not per entry — a Sync write that
        // touched only sourcePath must not "launder" an earlier manual checkoutDirectory edit on
        // the same entry into overwritable.
        val manualEntry = entries.indices.any { i ->
            val derived = derivation.perEntry[i]
            val cdDiffers = derived?.checkoutDirectory != entries[i].currentCheckoutDirectory
            val spDiffers = derived?.sourcePath != entries[i].currentSourcePath
            (cdDiffers && placementEditHistory.isCheckoutDirectoryManuallySet(componentId, entries[i].vcsPath)) ||
                (spDiffers && placementEditHistory.isSourcePathManuallySet(componentId, entries[i].vcsPath))
        }
        val manualBwd = bwdDiffers && placementEditHistory.isBuildWorkingDirectoryManuallySet(componentId, entries.first().vcsPath)
        return if (manualEntry || manualBwd) {
            PlacementDiffRowStatus.MANUAL_EDIT to
                listOf("the registry's current value was set by a real edit, not the V8 migration; not overwritten")
        } else {
            PlacementDiffRowStatus.RESOLVED to emptyList()
        }
    }

    /** [entries] are the row's raw entities (not [PlacementRegistryEntry]) so branch/tag/hotfixBranch
     * survive into the diff — Sync rebuilds a full replacement [VcsEntryRequest] from this, and those
     * fields would otherwise be silently cleared on write. */
    private fun toRowDiff(
        component: ComponentEntity,
        componentId: UUID,
        row: ComponentConfigurationEntity,
        rowLabel: String,
        entries: List<VcsSettingsEntryEntity>,
        status: PlacementDiffRowStatus,
        derivedPerEntry: Map<Int, PlacementValue>,
        derivedBwd: String?,
        notes: List<String>,
        sourceBuildTypeIds: List<String>,
    ): PlacementRowDiff =
        PlacementRowDiff(
            componentId = componentId,
            componentKey = component.componentKey,
            configurationRowId = row.id ?: componentId,
            versionRange = row.versionRange,
            rowLabel = rowLabel,
            status = status,
            entries = entries.mapIndexed { i, e ->
                val derived = derivedPerEntry[i]
                PlacementEntryDiff(
                    name = e.name,
                    vcsPath = e.vcsPath,
                    branch = e.branch,
                    tag = e.tag,
                    hotfixBranch = e.hotfixBranch,
                    repositoryType = e.repositoryType,
                    currentCheckoutDirectory = e.checkoutDirectory,
                    currentSourcePath = e.sourcePath,
                    derivedCheckoutDirectory = derived?.checkoutDirectory,
                    derivedSourcePath = derived?.sourcePath,
                )
            },
            currentBuildWorkingDirectory = row.buildWorkingDirectory,
            derivedBuildWorkingDirectory = derivedBwd,
            sourceBuildTypeIds = sourceBuildTypeIds,
            notes = notes,
            componentVersion = component.version,
        )

    // ------------------------------------------------------------------
    // TeamCity side: read every linked project's build types once per component, classify them.
    // ------------------------------------------------------------------

    private sealed interface ChainOutcome {
        data class Ok(
            val compileConfigs: List<TcCompileConfig>,
            val pausedCompileCount: Int,
            /** non-compile, non-paused build type id -> (repo keys it rules, root count). */
            val nonCompileRuled: Map<String, Pair<Set<String>, Int>>,
            val sourceBuildTypeIds: List<String>,
        ) : ChainOutcome

        data class Error(
            val message: String,
        ) : ChainOutcome
    }

    private fun fetchChain(
        component: ComponentEntity,
        projectIds: List<String>,
    ): ChainOutcome {
        val buildTypes = mutableListOf<ExternalBuildType>()
        for (projectId in projectIds) {
            try {
                val project = enrichedTcProjectFetcher.fetch(projectId) ?: continue
                buildTypes += project.buildTypes?.buildTypes.orEmpty()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                val message = describeTeamcityError(e, projectId)
                log.warn(e) {
                    "TeamCity placement diff: failed to read project '$projectId' for component '${component.componentKey}': $message"
                }
                return ChainOutcome.Error(message)
            }
        }
        val compile = mutableListOf<TcCompileConfig>()
        var pausedCompile = 0
        val nonCompileRuled = mutableMapOf<String, Pair<Set<String>, Int>>()
        for (bt in buildTypes) {
            val paused = bt.paused == true
            if (bt.isCompile()) {
                if (paused) pausedCompile++ else compile += bt.toCompileConfig()
            } else if (!paused) {
                val ruledKeys = bt
                    .vcsRootUrls()
                    .filter { !it.second.isNullOrBlank() }
                    .map { repoKey(it.first) }
                    .toSet()
                if (ruledKeys.isNotEmpty()) {
                    nonCompileRuled[bt.id] = ruledKeys to (bt.vcsRoots?.entries?.size ?: 0)
                }
            }
        }
        return ChainOutcome.Ok(compile, pausedCompile, nonCompileRuled, compile.map { it.buildTypeId })
    }

    /** Best-effort classification of a TeamCity fetch failure for the job/report; the design brief
     * asks specifically for a clear "no permission" message on a 403. */
    private fun describeTeamcityError(
        e: Exception,
        projectId: String,
    ): String {
        val text = "${e::class.simpleName.orEmpty()} ${e.message.orEmpty()}"
        return if (Regex("""\b403\b""").containsMatchIn(text) || text.contains("Forbidden", ignoreCase = true)) {
            "no permission to read VCS root entries for TeamCity project '$projectId'"
        } else {
            "TeamCity error reading project '$projectId': ${e.message ?: e::class.simpleName}"
        }
    }

    private companion object {
        const val BASE_ROW_LABEL = "BASE"
    }
}
