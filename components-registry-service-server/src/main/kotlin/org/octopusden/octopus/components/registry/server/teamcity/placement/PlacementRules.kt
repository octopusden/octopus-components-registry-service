package org.octopusden.octopus.components.registry.server.teamcity.placement

import java.util.Locale

/**
 * ONB-002: pure derivation of VCS root placement (Checkout Directory / Source Path / Build
 * Working Directory, ADR-001) from a component's TeamCity compile build configurations.
 *
 * A direct Kotlin port of the read-only one-off `placement_import.py` (and its
 * per-locator-error-handling successor) used to seed ADR-001's rev. 3 import, scoped down to what
 * the ONB-002 Diff/Sync jobs need: compile configurations only (`CDGradleBuild` /
 * `CDJavaMavenBuild`, non-paused), no `CDRelease` / `CdReleaseCandidateNew` fallback — the design
 * brief reads only the linked projects' compile build types. Dropping the release fallback also
 * drops Python's `release-only` status; what Python called `partial` (a row where every compile
 * configuration agrees on SOME entries but never attaches at least one entry at all) folds into
 * [PlacementRowStatus.UNEXPRESSIBLE] here — both mean "cannot be safely applied", and the per-entry
 * `notes` still say which entry is missing.
 *
 * No framework dependency on purpose: this is exercised entirely by [PlacementRulesTest] with
 * plain JUnit, one case per ported Python test.
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

enum class PlacementRowStatus {
    RESOLVED,
    CONFLICT,
    UNEXPRESSIBLE,
    NO_CHAIN,
    OUTSIDE_TEMPLATES,
    COMPILE_PAUSED,
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
private val PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9._-]+")
private const val CHECKOUT_DIR_VAR = "%teamcity.build.checkoutDir%"
private val CHECKOUT_RULE_PATTERN = Regex("""^\+:\s*(\S+?)(?:\s*=>\s*(\S+))?$""")

/** Reserved Checkout Directory names ADR-001 keeps out of the repository (compared ignoring case). */
val RESERVED_CHECKOUT_DIRECTORIES: Set<String> = setOf("report-templates", "sonar-config", "target", "sonar-report")

/**
 * Host- and scheme-agnostic `"<project>/<repo>"` in lower case, without a trailing `.git` — matches
 * a TeamCity VCS root URL to a registry `vcsPath` regardless of protocol, host or case. Port of
 * `placement_import.py`'s `repo_key`.
 */
fun repoKey(url: String?): String {
    var u = (url ?: "").trim().lowercase(Locale.ROOT).trimEnd('/')
    u = Regex("""\.git/?$""").replace(u, "")
    u = u.replace(':', '/')
    return u.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
}

/**
 * One checkout rule of a TeamCity VCS root entry -> a [PlacementValue], or `null` when the rule is
 * not one agent-side checkout can express (and so this model has no field for it). Port of
 * `placement_import.py`'s `parse_rule`.
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
    data class Path(val value: String?) : WorkDirParse

    object Unexpressible : WorkDirParse
}

/** `WORK_DIR` -> a Build Working Directory. Port of `placement_import.py`'s `parse_work_dir`. */
fun parseWorkDir(value: String?): WorkDirParse {
    var v = (value ?: CHECKOUT_DIR_VAR).trim().trimEnd('/')
    if (v == CHECKOUT_DIR_VAR) return WorkDirParse.Path(null)
    if (v.startsWith("$CHECKOUT_DIR_VAR/")) v = v.substring(CHECKOUT_DIR_VAR.length + 1)
    if (v.contains('%') || v.startsWith('/')) return WorkDirParse.Unexpressible
    val segments = v.split('/')
    return if (segments.all { PATH_SEGMENT_PATTERN.matches(it) && it != "." && it != ".." }) {
        WorkDirParse.Path(v)
    } else {
        WorkDirParse.Unexpressible
    }
}

/**
 * Derive the placement of one configuration row's VCS entries from its component's compile build
 * configurations. Port of `placement_import.py`'s `derive` (compile-only — see file kdoc).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
fun derive(input: DeriveInput): PlacementDerivation {
    val entries = input.entries
    val perEntrySeen = mutableMapOf<Int, MutableSet<PlacementValue>>()
    val unexpressible = mutableSetOf<Int>()
    val workDirSeen = mutableSetOf<WorkDirParse>()

    for (bt in input.compileConfigs) {
        val attached = bt.vcsRootEntries.associate { repoKey(it.url) to it.checkoutRules }
        var hit = false
        entries.forEachIndexed { i, row ->
            val key = repoKey(row.vcsPath)
            if (attached.containsKey(key)) {
                hit = true
                val parsed = parseCheckoutRule(attached[key])
                if (parsed == null) unexpressible.add(i) else perEntrySeen.getOrPut(i) { mutableSetOf() }.add(parsed)
            }
        }
        if (hit) workDirSeen.add(parseWorkDir(bt.workDir))
    }

    val notes = mutableListOf<String>()
    val result = mutableMapOf<Int, PlacementValue>()
    entries.forEachIndexed { i, row ->
        val seen = perEntrySeen[i].orEmpty()
        when {
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
            input.outsideRuledConfigCounts.entries.sortedBy { it.key }
                .joinToString(", ") { "`${it.key}` (${it.value} roots)" } + "; not derived"
    }

    if (input.compileConfigs.isEmpty()) {
        return when {
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
    }

    var status = when {
        unexpressible.isNotEmpty() || workDirUnexpressible -> PlacementRowStatus.UNEXPRESSIBLE
        notes.any { it.contains("disagree") } -> PlacementRowStatus.CONFLICT
        result.size == entries.size && resolvedWorkDir != null -> PlacementRowStatus.RESOLVED
        else -> PlacementRowStatus.UNEXPRESSIBLE // Python's "partial": some entry never attached anywhere known.
    }

    var finalNotes: List<String> = notes
    if (status == PlacementRowStatus.RESOLVED) {
        val problems = checkRules(entries, result, bwd)
        if (problems.isNotEmpty()) {
            status = PlacementRowStatus.UNEXPRESSIBLE
            finalNotes = notes + problems
        }
    }
    return PlacementDerivation(status, result, bwd, finalNotes)
}

/**
 * The ADR-001 rev. 3 registry rules a derived (or edited) placement must satisfy: at most one root
 * at the checkout root, distinct Checkout Directories (case-insensitively), none reserved, and a
 * Build Working Directory that starts inside a placed root (or the row has a root at the checkout
 * root). Port of `placement_import.py`'s `check_rules`.
 */
fun checkRules(
    entries: List<PlacementRegistryEntry>,
    result: Map<Int, PlacementValue>,
    bwd: String?,
): List<String> {
    val problems = mutableListOf<String>()
    val atRoot = result.filterValues { it.checkoutDirectory == null }.keys.map { entries[it].name }
    if (atRoot.size > 1) problems += "more than one root at the checkout root: $atRoot"
    val directories = result.values.mapNotNull { it.checkoutDirectory }
    if (directories.map { it.lowercase(Locale.ROOT) }.toSet().size != directories.size) {
        problems += "duplicate Checkout Directory"
    }
    if (directories.any { it.lowercase(Locale.ROOT) in RESERVED_CHECKOUT_DIRECTORIES }) {
        problems += "reserved Checkout Directory"
    }
    if (bwd == null && atRoot.isEmpty()) {
        problems += "every root has a Checkout Directory but WORK_DIR is the checkout root"
    }
    if (bwd != null) {
        val first = bwd.substringBefore('/')
        if (first !in directories && atRoot.isEmpty()) {
            problems += "Build Working Directory '$bwd' is outside every placed root"
        }
    }
    return problems
}
