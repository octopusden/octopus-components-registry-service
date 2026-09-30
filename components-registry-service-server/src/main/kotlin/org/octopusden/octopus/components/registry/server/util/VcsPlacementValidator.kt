package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.escrow.RepositoryType

/**
 * ADR-001 rev. 3 placement validation over a row's final VCS entries — extracted from
 * `ComponentManagementServiceImpl` (owner review of PR #510, finding 4) so the TeamCity placement
 * Diff job can run the SAME checks a real v4 write runs against a row's TeamCity-derived values,
 * to tell RESOLVED (apply-able) from INVALID (would be rejected), without duplicating the rule.
 *
 * Both callers construct a candidate `List<VcsSettingsEntryEntity>` (persisted, for a real PATCH;
 * transient, never saved, for the Diff job's what-if check) and pass it here unchanged.
 */
object VcsPlacementValidator {
    private val CHECKOUT_DIRECTORY_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")
    private val SOURCE_PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9._-]+")

    // vcs_settings_entries.source_path / checkout_directory are VARCHAR(255).
    private const val MAX_PLACEMENT_LENGTH = 255

    // Checkout-root directories the build templates write (compared ignoring case).
    val RESERVED_CHECKOUT_DIRECTORIES = setOf("report-templates", "sonar-config", "target", "sonar-report")

    /** A repository's identity in a row: Git (and other case-insensitive types) ignore case, as the model does on read. */
    fun repositoryKey(
        vcsPath: String,
        repositoryType: String?,
    ): String = if (RepositoryType.valueOf(repositoryType ?: "GIT").isCaseSensitive) vcsPath else vcsPath.lowercase()

    /**
     * ONB-001 placement rules over a row's final VCS entries: each entry is checked out under its
     * checkout directory, which is also its name, and at most one entry has none (it is checked out at
     * the checkout root). The first failure is a 400 `vcsEntries[<i>].<field>: <reason>`.
     */
    fun validateVcsPlacement(entries: List<VcsSettingsEntryEntity>) {
        var rootEntry: Int? = null
        val nameOwners = HashMap<String, Int>()
        val locationOwners = HashMap<Pair<String, String?>, Int>()
        entries.forEachIndexed { i, e ->
            fun fail(
                field: String,
                reason: String,
            ): Nothing = throw IllegalArgumentException("vcsEntries[$i].$field: $reason")

            fun label(k: Int) = "VCS root ${k + 1} (${repositoryName(entries[k].vcsPath)})"
            val suggestion = suggestedDirectory(e.vcsPath, entries.mapNotNull { it.checkoutDirectory })
            val dir = e.checkoutDirectory
            if (dir == null) {
                rootEntry?.let {
                    fail(
                        "checkoutDirectory",
                        "required: ${label(it)} is already checked out at the checkout root, and only one VCS root can be. " +
                            "Set a Checkout Directory for this VCS root, a folder name such as '$suggestion', " +
                            "or give one to VCS root ${it + 1}.",
                    )
                }
                rootEntry = i
            }
            checkoutDirectoryError(dir, suggestion)?.let { fail("checkoutDirectory", it) }
            nameOwners.putIfAbsent(e.name.lowercase(), i)?.let { fail("checkoutDirectory", nameCollision(e, entries[it], label(it))) }
            e.sourcePath?.let { path ->
                lengthError("Source Path", path)?.let { fail("sourcePath", it) }
                if (!isPlainRelativePath(path)) fail("sourcePath", relativePathError("Source Path", path, "services/api"))
            }
            locationOwners.putIfAbsent(repositoryKey(e.vcsPath, e.repositoryType) to e.sourcePath, i)?.let {
                val where = e.sourcePath?.let { "Source Path ('$it')" } ?: "Source Path (the whole repository)"
                fail(
                    "sourcePath",
                    "${label(it)} already checks out the same repository and $where. " +
                        "Change the Source Path, or remove one of the VCS roots.",
                )
            }
        }
    }

    /**
     * ONB-001 rev. 3: the Build Working Directory is a relative path that starts in the checkout
     * directory of one of the row's entries (compared case-sensitively: it is a path on the agent), or
     * anywhere below the checkout root when an entry is checked out there. When every entry has a
     * checkout directory it is required. Failures are a 400 `buildWorkingDirectory: <reason>`.
     */
    fun validateBuildWorkingDirectory(
        entries: List<VcsSettingsEntryEntity>,
        buildWorkingDirectory: String?,
    ) {
        fun fail(reason: String): Nothing = throw IllegalArgumentException("buildWorkingDirectory: $reason")
        val rootTaken = entries.any { it.checkoutDirectory == null }
        val directories = entries.mapNotNull { it.checkoutDirectory }
        if (buildWorkingDirectory == null) {
            if (entries.isNotEmpty() && !rootTaken) {
                val example = directories.first()
                fail(
                    "required: every VCS root has a Checkout Directory, so set the folder the build runs in, " +
                        "for example '$example' or '$example/app'.",
                )
            }
            return
        }
        lengthError("Build Working Directory", buildWorkingDirectory)?.let { fail(it) }
        if (!isPlainRelativePath(
                buildWorkingDirectory,
            )
        ) {
            fail(relativePathError("Build Working Directory", buildWorkingDirectory, "core/app"))
        }
        if (entries.isEmpty()) {
            fail("the row has no VCS roots, so the build has no folder to run in. Add a VCS root, or clear the Build Working Directory.")
        }
        val first = buildWorkingDirectory.substringBefore('/')
        if (!rootTaken && first !in directories) {
            fail(
                "'$buildWorkingDirectory' is not inside any checked-out VCS root. Start it with one of the Checkout Directories: " +
                    "${directories.joinToString(", ")}; or check one VCS root out at the checkout root (no Checkout Directory) " +
                    "to allow any folder.",
            )
        }
    }

    private fun isPlainRelativePath(path: String) =
        path.split('/').none {
            it == "." ||
                it == ".." ||
                !SOURCE_PATH_SEGMENT_PATTERN.matches(it)
        }

    private fun checkoutDirectoryError(
        dir: String?,
        suggestion: String,
    ): String? =
        when {
            dir == null -> null
            dir.length > MAX_PLACEMENT_LENGTH -> lengthError("Checkout Directory", dir)
            !CHECKOUT_DIRECTORY_PATTERN.matches(dir) ->
                "'$dir' is not a valid Checkout Directory: use a single folder name of letters, digits, '.', '_' or '-' " +
                    "that does not start with '.', for example 'app'."
            dir.lowercase() in RESERVED_CHECKOUT_DIRECTORIES ->
                "'$dir' is reserved: the build tooling uses that folder at the checkout root. " +
                    "Choose another folder name, for example '$suggestion'."
            else -> null
        }

    /** Why [entry]'s name clashes with [owner]'s, which came first in the row; names are compared ignoring case. */
    private fun nameCollision(
        entry: VcsSettingsEntryEntity,
        owner: VcsSettingsEntryEntity,
        ownerLabel: String,
    ): String =
        when {
            entry.checkoutDirectory == null ->
                "this VCS root is checked out at the checkout root under the name '${entry.name}', which $ownerLabel already uses. " +
                    "Set a Checkout Directory for this VCS root, or change the Checkout Directory of $ownerLabel."
            owner.checkoutDirectory == null ->
                "Checkout Directory '${entry.name}' is already the name of $ownerLabel, which is checked out at the checkout root. " +
                    "Names must be unique, ignoring case. Choose another folder name."
            else ->
                "Checkout Directory '${entry.name}' is already used by $ownerLabel. Each VCS root needs its own folder, " +
                    "and the comparison ignores case. Choose another folder name."
        }

    private fun lengthError(
        what: String,
        value: String,
    ): String? =
        if (value.length >
            MAX_PLACEMENT_LENGTH
        ) {
            "is ${value.length} characters long; a $what can have at most $MAX_PLACEMENT_LENGTH"
        } else {
            null
        }

    private fun relativePathError(
        what: String,
        value: String,
        example: String,
    ) = "'$value' is not a valid $what: use a relative path of '/'-separated folder names " +
        "(letters, digits, '.', '_', '-'; no '.' or '..'), for example '$example'."

    /**
     * A folder name to suggest for a VCS root: its repository's name when that is a valid Checkout
     * Directory no other root of the row uses (ignoring case), else a safe literal.
     */
    private fun suggestedDirectory(
        vcsPath: String,
        taken: List<String>,
    ): String {
        val used = taken.map { it.lowercase() }.toSet()
        // Unbounded fallback: a row has finitely many roots, so a free `app-<n>` always exists.
        return (sequenceOf(repositoryName(vcsPath), "app") + generateSequence(1) { it + 1 }.map { "app-$it" })
            .first { checkoutDirectoryError(it, "app") == null && it.lowercase() !in used }
    }

    /** How the Portal names a repository: the last segment of its path, without `.git`. */
    private fun repositoryName(vcsPath: String) =
        vcsPath
            .trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
            .removeSuffix(".git")
}
