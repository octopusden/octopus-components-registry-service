package org.octopusden.octopus.components.registry.server.teamcity.placement

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.validation.EnrichedTcProjectFetcher
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

    /** The component's linked TeamCity project(s) could not be read; see the row's notes for why. */
    TC_ERROR,
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
)

data class PlacementDiffResult(
    val generatedAt: Instant,
    val rows: List<PlacementRowDiff>,
)

/**
 * ONB-002: the read-only TeamCity -> CRS VCS-placement Diff. Walks every non-archived
 * component's configuration rows that carry VCS entries and have a TeamCity link, derives their
 * placement from the linked project(s)' compile build configurations ([derive]), and classifies
 * each row against the registry's current values.
 *
 * Scope (design brief): multi-root rows; single-root rows where TeamCity derives (or the registry
 * already carries) a non-root Checkout Directory; single-root rows with a non-default Build
 * Working Directory (current or derived). A single-root row with nothing on either side (no
 * Checkout Directory, checkout-root Build Working Directory) is out of scope entirely — ADR-001
 * keeps single-root Checkout Directories out of the escrow-facing `main` name — and never appears
 * in the result, not even as "in sync".
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

    /** [componentIds] restricts the walk to those components — used by Sync to re-derive just the
     * rows it is about to apply, against the same rules the original Diff used. */
    fun runDiff(componentIds: Set<UUID>? = null): PlacementDiffResult {
        val rows = componentConfigurationRepository
            .findAllNonArchivedRowsWithVcsEntries()
            .filter { componentIds == null || it.component.id in componentIds }
        val byComponent = rows.groupBy { it.component }
        val result = mutableListOf<PlacementRowDiff>()
        for ((component, componentRows) in byComponent) {
            val componentId = component.id
            val projectIds = componentId?.let { versionLineRepository.findDistinctTeamcityProjectIdsByComponentId(it) }.orEmpty()
            if (componentId == null || projectIds.isEmpty()) continue // no id, or no TeamCity link: nothing to diff against
            val chain = fetchChain(component, projectIds)
            for (row in componentRows) {
                diffRow(component, componentId, row, chain)?.let(result::add)
            }
        }
        log.info { "TeamCity placement diff: ${result.size} row(s) in scope across ${byComponent.size} linked component(s)" }
        return PlacementDiffResult(Instant.now(), result)
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
        val rowLabel = row.overriddenAttribute ?: "BASE"

        if (chain is ChainOutcome.Error) {
            if (!inScope(entries, row.buildWorkingDirectory, derivedCd = null, derivedBwd = null)) return null
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
        val repoKeys = placementEntries.map { repoKey(it.vcsPath) }.toSet()
        val outsideCounts = chain.nonCompileRuled.filterValues { it.first.any { key -> key in repoKeys } }.mapValues { it.value.second }
        val derivation = derive(DeriveInput(placementEntries, chain.compileConfigs, chain.pausedCompileCount, outsideCounts))

        val derivedCdForScope = derivation.perEntry.values
            .firstOrNull()
            ?.checkoutDirectory
        if (!inScope(entries, row.buildWorkingDirectory, derivedCdForScope, derivation.buildWorkingDirectory)) return null

        val (status, extraNotes) = finalizeStatus(componentId, placementEntries, row.buildWorkingDirectory, derivation)
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

    /** Scope filter — see class kdoc. Only single-root rows are filtered; a multi-root row is always in scope. */
    private fun inScope(
        entries: List<VcsSettingsEntryEntity>,
        currentBwd: String?,
        derivedCd: String?,
        derivedBwd: String?,
    ): Boolean {
        if (entries.size != 1) return true
        return entries.single().checkoutDirectory != null || currentBwd != null || derivedCd != null || derivedBwd != null
    }

    /**
     * Layers MANUAL_EDIT / IN_SYNC over a RESOLVED derivation by comparing it against the row's
     * current values; passes every other status straight through.
     */
    private fun finalizeStatus(
        componentId: UUID,
        entries: List<PlacementRegistryEntry>,
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
        val manualEntry = differingEntries.any { i -> placementEditHistory.wasEverManuallyPlaced(componentId, entries[i].vcsPath) }
        val manualBwd = bwdDiffers && placementEditHistory.wasBuildWorkingDirectoryEverManuallySet(componentId)
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
            val templateIds = (
                bt.templates
                    ?.buildTypes
                    .orEmpty()
                    .map { it.id } + listOfNotNull(bt.template?.id)
            ).toSet()
            val isCompile = templateIds.any { it in COMPILE_TEMPLATE_IDS }
            val paused = bt.paused == true
            if (isCompile) {
                if (paused) {
                    pausedCompile++
                } else {
                    compile += TcCompileConfig(
                        buildTypeId = bt.id,
                        vcsRootEntries = bt.vcsRoots?.entries.orEmpty().map { e ->
                            TcVcsRootEntry(
                                url = e.vcsRoot.properties
                                    ?.properties
                                    .orEmpty()
                                    .firstOrNull { it.name == "url" }
                                    ?.value,
                                checkoutRules = e.checkoutRules,
                            )
                        },
                        workDir = bt.parameters
                            ?.properties
                            .orEmpty()
                            .firstOrNull { it.name == "WORK_DIR" }
                            ?.value,
                    )
                }
            } else if (!paused) {
                val ruledKeys = bt.vcsRoots
                    ?.entries
                    .orEmpty()
                    .filter { !it.checkoutRules.isNullOrBlank() }
                    .map { e ->
                        repoKey(
                            e.vcsRoot.properties
                                ?.properties
                                .orEmpty()
                                .firstOrNull { it.name == "url" }
                                ?.value,
                        )
                    }.toSet()
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
        /** ADR-001: the only two chain templates whose build carries `WORK_DIR` / checkout placement. */
        val COMPILE_TEMPLATE_IDS = setOf("CDGradleBuild", "CDJavaMavenBuild")
    }
}
