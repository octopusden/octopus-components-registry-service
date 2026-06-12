package org.octopusden.octopus.components.registry.server.util

/** One persisted configuration row's jira-relevant scalars, for effective-pair computation. */
internal data class JiraRowView(
    val componentKey: String,
    val versionRange: String,
    val rowType: String,
    val overriddenAttribute: String?,
    val projectKey: String?,
    val versionPrefix: String?,
)

/**
 * EFFECTIVE jira `(projectKey, versionPrefix)` claims per component, reconstructed from
 * persisted configuration rows the way the resolver serves them (and the way the legacy
 * validator bucketed merged DSL configs): per-range SCALAR_OVERRIDE values LAYER over the
 * BASE row. A projectKey-only override range claims `(overrideKey, BASE prefix)` — not
 * `(overrideKey, null)`; bucketing raw rows fabricated `(key, null)` claims and falsely
 * conflicted with components that legitimately own the no-prefix bucket (a real prod shape).
 *
 * Override rows store one attribute each (`overriddenAttribute`), so rows of one range
 * merge first; `overriddenAttribute` — not value nullness — decides whether an attribute
 * was overridden (a null-clear override IS an override). Blank/absent effective projectKey
 * claims nothing.
 */
internal fun computeEffectiveJiraPairs(rows: List<JiraRowView>): Map<String, Set<Pair<String, String?>>> =
    rows.groupBy { it.componentKey }.mapValues { (_, componentRows) ->
        val base = componentRows.firstOrNull { it.rowType == "BASE" }
        val pairs = mutableSetOf<Pair<String, String?>>()
        base?.projectKey?.takeIf { it.isNotBlank() }?.let { pairs += it to base.versionPrefix }
        componentRows
            .filter { it.rowType != "BASE" }
            .groupBy { it.versionRange }
            .forEach { (_, rangeRows) ->
                val pkRow = rangeRows.firstOrNull { it.overriddenAttribute == "jira.projectKey" }
                val prefixRow = rangeRows.firstOrNull { it.overriddenAttribute == "jira.versionPrefix" }
                if (pkRow == null && prefixRow == null) return@forEach
                val effectivePk =
                    (if (pkRow != null) pkRow.projectKey else base?.projectKey)
                        ?.takeIf { it.isNotBlank() } ?: return@forEach
                val effectivePrefix = if (prefixRow != null) prefixRow.versionPrefix else base?.versionPrefix
                pairs += effectivePk to effectivePrefix
            }
        pairs
    }
