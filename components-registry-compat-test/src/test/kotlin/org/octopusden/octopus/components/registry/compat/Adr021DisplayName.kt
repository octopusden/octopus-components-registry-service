package org.octopusden.octopus.components.registry.compat

/**
 * ADR-021 — the ONE display-name transition the compat gate tolerates.
 *
 * `JiraComponent.displayName` now resolves `jiraDisplayName ?: displayName`, so every component
 * that declared only a `componentDisplayName` reports its own name where the 2.0.87 baseline
 * reported `null`. That is intended. Everything else about the field is not:
 *
 * | baseline | candidate | verdict |
 * |---|---|---|
 * | `null`/absent | non-blank name | equal — the intended fallback |
 * | `"A"` | `"A"` | equal — unchanged |
 * | `"A"` | `"B"` | **differ** — a renamed component is a real change |
 * | `"A"` | `null` | **differ** — a name disappearing is a regression |
 * | `null` | `""` / blank | **differ** — blank is not a name (see the isNotBlank guard in the mapper) |
 *
 * Deliberately direction-asymmetric: it forgives gaining a name, never losing or changing one.
 *
 * **DB-mode only.** ADR-021 applies the fallback on the DB resolver path alone; a git-routed
 * candidate (`compat.candidate.mode=git`, the id18 / [2.3] gate) must stay byte-identical to the
 * 2.0.87 baseline — that is the deploy-without-migration no-op invariant `known-deltas-git.json`
 * encodes by being empty. Without this gate the suppression would apply there too, and a git-mode
 * candidate that wrongly GAINED a display name would produce zero diffs: the typed layer would
 * forgive it and the raw layer sees two strings of the same JSON type. The empty git-mode
 * known-deltas file cannot catch that, because nothing would be recorded to suppress.
 */
object Adr021DisplayName {
    /**
     * Resolved once from [CompatConfig] at class initialisation — real runs set
     * `compat.candidate.mode` before the test JVM starts. Mutable so a test can exercise the
     * git-mode branch; [Adr021DisplayNameCompatTest] restores it.
     */
    @Volatile
    var gitMode: Boolean = CompatConfig.load().gitMode

    fun equal(
        baseline: Any?,
        candidate: Any?,
    ): Boolean {
        if (baseline == candidate) return true
        // Git-mode candidates get no allowance at all — see the class doc.
        if (gitMode) return false
        val baselineAbsent = baseline == null || (baseline is String && baseline.isBlank())
        return baselineAbsent && candidate is String && candidate.isNotBlank()
    }
}
