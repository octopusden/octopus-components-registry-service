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
 */
object Adr021DisplayName {
    fun equal(
        baseline: Any?,
        candidate: Any?,
    ): Boolean {
        if (baseline == candidate) return true
        val baselineAbsent = baseline == null || (baseline is String && baseline.isBlank())
        return baselineAbsent && candidate is String && candidate.isNotBlank()
    }
}
