package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Decides whether an `ARRAY_SIZE_MISMATCH` on the `jira-component-version-ranges` endpoints is the
 * TD-022 recovery that ADR-021 triggers — and refuses every other superset.
 *
 * `JiraComponentVersionRange.equals/hashCode` omit `componentName` (TD-022), so two components whose
 * range and Jira configuration are identical collapse into ONE element of the returned Set. A
 * component that declares only a `componentDisplayName` had `component.displayName = null` in the
 * baseline, which is what made it identical to its siblings; ADR-021 resolves that name, the
 * elements stop being equal, and the component reappears.
 *
 * Confirming that story — rather than accepting "the candidate has more elements" — is the whole
 * point. An addition is accepted only when ALL of these hold:
 *
 *  1. Nothing is lost: every baseline element pairs with its own DISTINCT candidate element.
 *  2. The added element's `componentName` is absent from the baseline entirely — it was invisible,
 *     not duplicated.
 *  3. A baseline **twin** exists: same `versionRange`, a DIFFERENT `componentName`, and a
 *     byte-identical `component` once `displayName` is set aside. That twin IS the element the
 *     addition was collapsing into.
 *  4. The twin's `displayName` is absent/blank and the addition's is non-blank — i.e. the
 *     un-collapse is caused by the name appearing, which is ADR-021 and nothing else.
 *
 * Any other size difference — a loss, a duplicate of a visible component, an addition with no twin,
 * an addition whose twin already had a name — is [Verdict.Rejected] and stays an active diff.
 *
 * Elements are keyed WITHOUT `displayName` so the 518 components whose name merely appears still
 * pair with their baseline selves; their value change is a separate, separately-suppressed record.
 */
object Adr021RangeRecovery {
    /** Marker written into the raw record's message. The known-delta entry keys on this exact text. */
    const val CONFIRMED_MARKER = "TD-022 RECOVERY CONFIRMED"

    sealed interface Verdict {
        /** The size difference is exactly the TD-022 recovery; [keys] identifies the recovered elements. */
        data class Confirmed(
            val keys: Set<String>,
        ) : Verdict

        /** Not a case this rule speaks about (not two arrays, or equal membership). */
        data object NotApplicable : Verdict

        /** A size difference this rule refuses to explain. [reason] goes into the diff message. */
        data class Rejected(
            val reason: String,
        ) : Verdict
    }

    /** `componentName` + `versionRange` — stable enough to name an element in a message. */
    fun keyOf(element: JsonNode): String =
        "componentName=${element.path("componentName").asText()}, versionRange=${element.path("versionRange").asText()}"

    /**
     * The element as the OLD equality contract saw it: `componentName` dropped (that is TD-022 —
     * `JiraComponentVersionRange.equals` ignores it) and `component.displayName` dropped (that is
     * the field ADR-021 changes). Two elements sharing this identity are exactly the pair that
     * collapsed in the baseline and separates in the candidate.
     */
    private fun identityOf(element: JsonNode): String {
        val copy = element.deepCopy<JsonNode>()
        (copy as? ObjectNode)?.remove("componentName")
        ((copy as? ObjectNode)?.get("component") as? ObjectNode)?.remove("displayName")
        return copy.toString()
    }

    private fun pairingKeyOf(element: JsonNode): Pair<String, String> = identityOf(element) to element.path("componentName").asText()

    private fun displayNameOf(element: JsonNode): String? =
        element
            .path("component")
            .path("displayName")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.asText()

    private fun isBlankName(name: String?) = name == null || name.isBlank()

    fun analyse(
        baseline: JsonNode?,
        candidate: JsonNode?,
    ): Verdict {
        val base = baseline?.takeIf { it.isArray } ?: return Verdict.NotApplicable
        val cand = candidate?.takeIf { it.isArray } ?: return Verdict.NotApplicable
        // ADR-021 changes the DB resolver only; a git-routed candidate must stay byte-identical.
        if (Adr021DisplayName.gitMode) return Verdict.Rejected("git-mode candidate gets no recovery allowance")

        val baselineCounts = base.groupingBy { pairingKeyOf(it) }.eachCount()
        val candidateCounts = cand.groupingBy { pairingKeyOf(it) }.eachCount()

        val lost = baselineCounts.filter { (key, n) -> n > (candidateCounts[key] ?: 0) }
        if (lost.isNotEmpty()) {
            return Verdict.Rejected("${lost.size} baseline element(s) unmatched — a loss is never a recovery")
        }

        val extras = cand.filter { (candidateCounts[pairingKeyOf(it)] ?: 0) > (baselineCounts[pairingKeyOf(it)] ?: 0) }
        if (extras.isEmpty()) return Verdict.NotApplicable

        val baselineNames = base.map { it.path("componentName").asText() }.toSet()
        for (extra in extras) {
            val rejection = reject(extra, base, baselineNames)
            if (rejection != null) return Verdict.Rejected(rejection)
        }
        return Verdict.Confirmed(extras.map { keyOf(it) }.toSet())
    }

    /**
     * Why the twin search failed. A bare "no twin" is as undiagnosable as the bare size mismatch was:
     * the collapse needs `versionRange`, `component` (modulo `displayName`), `distribution` AND
     * `vcsSettings` to be identical, and knowing WHICH of them diverges is the difference between a
     * one-line fix and another full compat run. Reports the closest baseline element by that measure.
     */
    private fun nearestMiss(
        extra: JsonNode,
        baseline: JsonNode,
    ): String {
        val parts = listOf("component", "distribution", "vcsSettings")
        val sameRange = baseline.filter { it.path("versionRange").asText() == extra.path("versionRange").asText() }
        if (sameRange.isEmpty()) return "no baseline element shares its versionRange"
        val best =
            sameRange.maxByOrNull { candidateTwin ->
                parts.count { field -> fieldMatches(field, candidateTwin, extra) }
            }!!
        val differing = parts.filterNot { fieldMatches(it, best, extra) }
        val nameOfBest = best.path("componentName").asText()
        return "${sameRange.size} baseline element(s) share the versionRange; the closest differs in " +
            "${differing.joinToString("+")} (its componentName=$nameOfBest, " +
            "displayName=${displayNameOf(best) ?: "<absent>"})"
    }

    /** Field-level equality, with `component` compared without its `displayName`. */
    private fun fieldMatches(
        field: String,
        left: JsonNode,
        right: JsonNode,
    ): Boolean =
        if (field == "component") {
            componentWithoutName(left) == componentWithoutName(right)
        } else {
            left.path(field) == right.path(field)
        }

    private fun componentWithoutName(element: JsonNode): String {
        val copy = element.path("component").deepCopy<JsonNode>()
        (copy as? ObjectNode)?.remove("displayName")
        return copy.toString()
    }

    /** The per-element half of [analyse]: `null` when this addition is a confirmed TD-022 recovery. */
    private fun reject(
        extra: JsonNode,
        baseline: JsonNode,
        baselineNames: Set<String>,
    ): String? {
        val name = extra.path("componentName").asText()
        if (name in baselineNames) {
            return "added element duplicates a component already present in the baseline: ${keyOf(extra)}"
        }
        if (isBlankName(displayNameOf(extra))) {
            return "added element carries no display name, so ADR-021 cannot explain it: ${keyOf(extra)}"
        }
        val identity = identityOf(extra)
        val twin =
            baseline.firstOrNull {
                identityOf(it) == identity &&
                    it.path("componentName").asText() != name &&
                    isBlankName(displayNameOf(it))
            }
        return if (twin == null) {
            "no baseline twin for ${keyOf(extra)} — the element was not collapsing under TD-022" +
                " (${nearestMiss(extra, baseline)})"
        } else {
            null
        }
    }
}
