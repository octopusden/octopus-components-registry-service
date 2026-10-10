package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer

/*
 * ONB-002: pure derivation of VCS root placement (Checkout Directory / Source Path / Build
 * Working Directory, ADR-001) from a component's TeamCity compile build configurations.
 *
 * Scoped to what the ONB-002 Diff/Sync jobs need: compile configurations only (`CDGradleBuild` /
 * `CDJavaMavenBuild`, non-paused), no `CDRelease` / `CdReleaseCandidateNew` fallback — the design
 * brief reads only the linked projects' compile build types. A row where every compile
 * configuration agrees on SOME entries but never attaches at least one entry at all is
 * PlacementRowStatus.UNEXPRESSIBLE — both mean "cannot be safely applied", and the per-entry
 * `notes` still say which entry is missing.
 *
 * No framework dependency on purpose: this is exercised entirely by PlacementRulesTest with
 * plain JUnit.
 */

/** One registry VCS entry as read from `vcs_settings_entries`, current CRS-stored placement included. */
data class PlacementRegistryEntry(
    val name: String,
    val vcsPath: String,
    val repositoryType: String?,
    val currentSourcePath: String?,
    val currentCheckoutDirectory: String?,
)

/** A resolved (or current) Checkout Directory / Source Path pair. `null` = the checkout root / whole repository. */
data class PlacementValue(
    val checkoutDirectory: String?,
    val sourcePath: String?,
)

/** One VCS root entry attached to a TeamCity build configuration (from the enriched fetch). */
data class TcVcsRootEntry(
    val url: String?,
    val checkoutRules: String?,
)

/** One non-paused compile build configuration (`CDGradleBuild` / `CDJavaMavenBuild`) of a linked TeamCity project. */
data class TcCompileConfig(
    val buildTypeId: String,
    val vcsRootEntries: List<TcVcsRootEntry>,
    val workDir: String?,
)

/**
 * [scopeFiltered]: the status carries derived values (or none by design), so a single-root row with
 * nothing on either side may be dropped from the Diff. A status that is NOT scope-filtered is
 * always reported; a new status is reported by default.
 */
enum class PlacementRowStatus(
    val scopeFiltered: Boolean = false,
) {
    RESOLVED(scopeFiltered = true),
    CONFLICT,
    UNEXPRESSIBLE,
    NO_CHAIN(scopeFiltered = true),
    OUTSIDE_TEMPLATES(scopeFiltered = true),
    COMPILE_PAUSED(scopeFiltered = true),
}

data class DeriveInput(
    val entries: List<PlacementRegistryEntry>,
    val compileConfigs: List<TcCompileConfig>,
    val pausedCompileCount: Int,
    /** Non-paused, non-compile build type id -> VCS root count, for the ones that attach one of
     * this row's repositories with a checkout rule of their own (evidence only; never derived from). */
    val outsideRuledConfigCounts: Map<String, Int>,
)

data class PlacementDerivation(
    val status: PlacementRowStatus,
    val perEntry: Map<Int, PlacementValue>,
    val buildWorkingDirectory: String?,
    val notes: List<String>,
)

/** Directory-name shape agent-side checkout (and this model) can express as a Checkout Directory. */
private val CHECKOUT_DIRECTORY_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")
private const val CHECKOUT_DIR_VAR = "%teamcity.build.checkoutDir%"
private val CHECKOUT_RULE_PATTERN = Regex("""^\+:\s*(\S+?)(?:\s*=>\s*(\S+))?$""")

/**
 * Matches a TeamCity VCS root URL to a registry `vcsPath` by their FULL canonical form (scheme
 * ignored, host INCLUDED, Git-case-insensitive, trailing `.git` stripped) — the same rule
 * [VcsUrlCanonicalizer] already applies to cross-component VCS-path comparisons, reused here rather
 * than re-implemented. Matching on only the last two
 * path segments would silently match same-named repositories on different hosts.
 */
fun repoKey(url: String?): String = VcsUrlCanonicalizer.canonicalize(url ?: "")

/**
 * One checkout rule of a TeamCity VCS root entry -> a [PlacementValue], or `null` when the rule is
 * not one agent-side checkout can express (and so this model has no field for it)..
 */
fun parseCheckoutRule(rules: String?): PlacementValue? {
    val lines = (rules ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return PlacementValue(null, null)
    if (lines.size != 1) return null
    val m = CHECKOUT_RULE_PATTERN.find(lines.single()) ?: return null
    val src = m.groupValues[1].trimEnd('/')
    val dst = m.groupValues[2].ifEmpty { null }?.trimEnd('/') ?: src
    if (src == ".") {
        return if (CHECKOUT_DIRECTORY_PATTERN.matches(dst)) PlacementValue(dst, null) else null
    }
    if (dst == src) return PlacementValue(null, src)
    if (dst.endsWith("/$src")) {
        val cd = dst.removeSuffix("/$src")
        return if (CHECKOUT_DIRECTORY_PATTERN.matches(cd)) PlacementValue(cd, src) else null
    }
    return null // remapping a subfolder: agent-side checkout can't, the model doesn't store it
}

/** Outcome of parsing `WORK_DIR`: a resolved (possibly root) path, or not expressible at all. */
sealed interface WorkDirParse {
    /** `value == null` means the checkout root. */
    data class Path(
        val value: String?,
    ) : WorkDirParse

    object Unexpressible : WorkDirParse
}

/**
 * `WORK_DIR` -> a Build Working Directory. It does not reject a segment shape or a leading `/`:
 * those are CRS VALIDATION rules (`util.VcsPlacementValidator.validateBuildWorkingDirectory`'s
 * `isPlainRelativePath`), not SHAPES this engine can't parse — pre-empting that check here would
 * repeat the exact mistake `PlacementRules.checkRules` was deleted for. A `%`-containing value (a
 * TeamCity property reference, e.g. `%CUSTOMIZATION_APP_PATH%`) is the one genuine parse failure:
 * it names a value this engine cannot resolve at all, not a value that resolves but fails CRS's
 * own rules.
 */
fun parseWorkDir(value: String?): WorkDirParse {
    var v = (value ?: CHECKOUT_DIR_VAR).trim().trimEnd('/')
    if (v == CHECKOUT_DIR_VAR) return WorkDirParse.Path(null)
    if (v.startsWith("$CHECKOUT_DIR_VAR/")) v = v.substring(CHECKOUT_DIR_VAR.length + 1)
    return if (v.contains('%')) WorkDirParse.Unexpressible else WorkDirParse.Path(v)
}

private class CompileConfigScan(
    val perEntrySeen: Map<Int, Set<PlacementValue>>,
    val unexpressible: Set<Int>,
    val conflictingWithinBuildType: Set<Int>,
    val workDirSeen: Set<WorkDirParse>,
)

private fun scanCompileConfigs(input: DeriveInput): CompileConfigScan {
    val entries = input.entries
    val perEntrySeen = mutableMapOf<Int, MutableSet<PlacementValue>>()
    val unexpressible = mutableSetOf<Int>()
    // A repository attached twice in ONE build type with two
    // DIFFERENT resolvable interpretations is a CONFLICT (same as two build types disagreeing),
    // not UNEXPRESSIBLE -- reserved for a rule SHAPE that can't be parsed at all.
    val conflictingWithinBuildType = mutableSetOf<Int>()
    val workDirSeen = mutableSetOf<WorkDirParse>()
    val registryKeys = entries.map { repoKey(it.vcsPath) }.toSet()

    for (bt in input.compileConfigs) {
        // Keyed by every checkout rule seen for that repo in this one build type, PARSED first
        // (not just the last, and not the raw string) — so a build type attaching the same
        // repository twice only conflicts when the rules actually resolve differently
        // (`+:mapper` and `+:mapper => mapper` are the same [PlacementValue] even though the raw
        // text differs), while a genuine disagreement still does and a lone unparseable rule is
        // still unexpressible.
        val attached: Map<String, List<PlacementValue?>> =
            bt.vcsRootEntries.groupBy({ repoKey(it.url) }, { parseCheckoutRule(it.checkoutRules) })
        entries.forEachIndexed { i, row ->
            val parsedRules = attached[repoKey(row.vcsPath)] ?: return@forEachIndexed
            val distinct = parsedRules.filterNotNull().toSet()
            when {
                // ANY unparseable rule for this repo in this build type is a SHAPE problem —
                // stays UNEXPRESSIBLE regardless of what else attaches the same repository
                // (a null parse must not be treated as just another "distinct value" that a
                // resolvable duplicate could turn into a CONFLICT).
                parsedRules.any { it == null } -> unexpressible.add(i)
                distinct.size > 1 -> conflictingWithinBuildType.add(i)
                else -> perEntrySeen.getOrPut(i) { mutableSetOf() }.add(distinct.single())
            }
        }
        if (bt.attachesAnyOf(registryKeys)) workDirSeen.add(parseWorkDir(bt.workDir))
    }
    return CompileConfigScan(perEntrySeen, unexpressible, conflictingWithinBuildType, workDirSeen)
}

/**
 * Derive the placement of one configuration row's VCS entries from its component's compile build
 * configurations.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
fun derive(input: DeriveInput): PlacementDerivation {
    val entries = input.entries
    val scan = scanCompileConfigs(input)
    val perEntrySeen = scan.perEntrySeen
    val unexpressible = scan.unexpressible
    val conflictingWithinBuildType = scan.conflictingWithinBuildType
    val workDirSeen = scan.workDirSeen

    val notes = mutableListOf<String>()
    val result = mutableMapOf<Int, PlacementValue>()
    entries.forEachIndexed { i, row ->
        val seen = perEntrySeen[i].orEmpty()
        when {
            i in conflictingWithinBuildType -> notes += "${row.name}: configurations disagree (attached twice with different rules)"
            i in unexpressible -> notes += "${row.name}: checkout rule not expressible"
            seen.size > 1 -> notes += "${row.name}: configurations disagree ($seen)"
            seen.isNotEmpty() -> result[i] = seen.single()
            else -> notes += "${row.name}: not attached in any chain configuration on a known template"
        }
    }

    val workDirUnexpressible = workDirSeen.any { it is WorkDirParse.Unexpressible }
    var resolvedWorkDir: WorkDirParse.Path? = null
    if (workDirSeen.size == 1 && !workDirUnexpressible) {
        resolvedWorkDir = workDirSeen.single() as WorkDirParse.Path
    } else if (workDirSeen.isNotEmpty()) {
        notes += "WORK_DIR: ${if (workDirSeen.size > 1) "disagree" else "not a plain path"} ($workDirSeen)"
    }
    val bwd = resolvedWorkDir?.value

    if (input.outsideRuledConfigCounts.isNotEmpty()) {
        notes += "configurations on other templates attach roots with checkout rules: " +
            input.outsideRuledConfigCounts.entries
                .sortedBy { it.key }
                .joinToString(", ") { "`${it.key}` (${it.value} roots)" } + "; not derived"
    }

    if (input.compileConfigs.isEmpty()) return noCompileConfigDerivation(input, result, bwd, notes)

    var status = when {
        unexpressible.isNotEmpty() || workDirUnexpressible -> PlacementRowStatus.UNEXPRESSIBLE
        notes.any { it.contains("disagree") } -> PlacementRowStatus.CONFLICT
        result.size == entries.size && resolvedWorkDir != null -> PlacementRowStatus.RESOLVED
        else -> PlacementRowStatus.UNEXPRESSIBLE // some entry never attached anywhere known.
    }

    return PlacementDerivation(status, result, bwd, notes)
}

private fun noCompileConfigDerivation(
    input: DeriveInput,
    result: Map<Int, PlacementValue>,
    bwd: String?,
    notes: List<String>,
): PlacementDerivation =
    when {
        input.outsideRuledConfigCounts.isNotEmpty() ->
            PlacementDerivation(PlacementRowStatus.OUTSIDE_TEMPLATES, result, bwd, notes)
        input.pausedCompileCount > 0 ->
            PlacementDerivation(
                PlacementRowStatus.COMPILE_PAUSED,
                result,
                bwd,
                notes + "${input.pausedCompileCount} compile configuration(s), all paused",
            )
        else ->
            PlacementDerivation(PlacementRowStatus.NO_CHAIN, result, bwd, notes + "no chain configuration found")
    }

/** A compile configuration belongs to a component when it attaches at least one of the component's own repositories. */
private fun TcCompileConfig.attachesAnyOf(registryKeys: Set<String>): Boolean = vcsRootEntries.any { repoKey(it.url) in registryKeys }

/** A repository a compile configuration of the component attaches that the registry does not list, with the build types that attach it. */
data class ExtraVcsRoot(
    val repo: String,
    val buildTypeIds: List<String>,
)

/** [extra]: attached in TeamCity, absent from the registry. [missing]: in the registry, attached by no compile configuration. */
data class VcsRootsComparison(
    val extra: List<ExtraVcsRoot>,
    val missing: List<String>,
)

fun compareVcsRoots(
    registryVcsPaths: List<String>,
    compileConfigs: List<TcCompileConfig>,
): VcsRootsComparison {
    // No compile configuration: nothing to compare against (the Diff reports that as NO_CHAIN etc.).
    if (compileConfigs.isEmpty()) return VcsRootsComparison(emptyList(), emptyList())
    val registryKeys = registryVcsPaths.map { repoKey(it) }.toSet()
    val attached = compileConfigs.flatMap { cc -> cc.vcsRootEntries.map { it.url to cc.buildTypeId } }
    val attachedKeys = attached.map { repoKey(it.first) }.toSet()
    // A sibling component's or an old version line's configuration in a shared or other project must
    // not make its roots "extra".
    val judged = compileConfigs
        .filter { it.attachesAnyOf(registryKeys) }
        .flatMap { cc -> cc.vcsRootEntries.map { it.url to cc.buildTypeId } }
    val extra = judged
        .filter { repoKey(it.first) !in registryKeys }
        .groupBy({ repoKey(it.first) }, { it })
        .values
        .map { group -> ExtraVcsRoot(repoDisplayName(group.first().first), group.map { it.second }.distinct()) }
    return VcsRootsComparison(extra, registryVcsPaths.filter { repoKey(it) !in attachedKeys })
}

/** `group/repo` — the last two path segments of the canonical URL, for messages. */
fun repoDisplayName(url: String?): String = repoKey(url).split('/').takeLast(2).joinToString("/")
