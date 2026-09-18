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
 *  4. The twin and the addition agree on a `displayName` the OLD contract could collapse on:
 *     either both blank, or both the same non-blank text, or the twin blank and the addition named.
 *     The first two are the repaired contract itself; only the third is ADR-021.
 *
 * Any other size difference — a loss, a duplicate of a visible component, an addition with no twin,
 * an addition whose twin carried a DIFFERENT name — is [Verdict.Rejected] and stays an active diff.
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
            val notes: List<String> = emptyList(),
        ) : Verdict

        /** Not a case this rule speaks about (not two arrays, or equal membership). */
        data object NotApplicable : Verdict

        /** A size difference this rule refuses to explain. [reason] goes into the diff message. */
        data class Rejected(
            val reason: String,
        ) : Verdict
    }

    /**
     * Why a pair that used to be one element is now two. The old `hashCode` included `displayName`
     * while the old `equals` excluded it, so what actually collapsed a pair was an EQUAL name —
     * blank or not. That is the repaired contract's own effect, shared by both resolvers. Only the
     * asymmetric case, where a name appears where there was none, is ADR-021 and DB-only.
     */
    private enum class Cause { CONTRACT, ADR_021 }

    private data class Twin(
        val element: JsonNode,
        val cause: Cause,
    )

    /** `componentName` + `versionRange` — stable enough to name an element in a message. */
    fun keyOf(element: JsonNode): String =
        "componentName=${element.path("componentName").asText()}, versionRange=${element.path("versionRange").asText()}"

    /**
     * The whole element minus `componentName` and `component.displayName`. Used to PAIR an element
     * with its own baseline self across the ADR-021 rename, so "nothing was lost" stays a strict
     * check: a `distribution` change on a surviving element is still a difference.
     */
    private fun fullIdentityOf(element: JsonNode): String {
        val copy = element.deepCopy<JsonNode>()
        (copy as? ObjectNode)?.remove("componentName")
        ((copy as? ObjectNode)?.get("component") as? ObjectNode)?.remove("displayName")
        // Apply the normalisations the COMPARISON already forgives, or pairing disagrees with it: a
        // trailing comma in the GAV CSV is a formatting artefact of the DB resolver (see
        // GavCsvComparator), and treating it as a difference here reported the surviving twin as a
        // lost baseline element and refused the whole recovery.
        ((copy as? ObjectNode)?.get("distribution") as? ObjectNode)?.let { distribution ->
            distribution.get("GAV")?.takeIf { it.isTextual }?.let {
                distribution.put("GAV", GavCsvComparator.normalize(it.asText()))
            }
        }
        return copy.toString()
    }

    /**
     * The element as the OLD equality contract actually saw it — narrower than the element itself.
     * `JiraComponentVersionRange.equals` compares `versionRange`, `jiraComponent`, `distribution` and
     * `vcsSettings`, but:
     *
     *  - `componentName` is not compared at all (TD-022);
     *  - `JiraComponent.equals` excludes `displayName`, while its `hashCode` includes it — the
     *    violated contract that makes the collapse come apart once the name resolves;
     *  - `Distribution` is a Groovy class carrying `@EqualsAndHashCode` over `private final` FIELDS.
     *    That transform compares *properties*, and a private field is not a property, so the
     *    generated `equals` compares nothing beyond the type: any two Distributions are equal.
     *    Confirmed in the generated bytecode — neither `equals` nor `hashCode` reads a field.
     *
     * So `distribution` is deliberately NOT part of this identity. Including it models a stricter
     * contract than production runs, which is exactly what made this rule refuse a real recovery.
     * The divergence is still REPORTED rather than silently accepted.
     *
     * `hotfixEnabled` is in `JiraComponent.equals` but absent from the DTO, so this identity is a
     * subset of the real contract — more permissive about that one field, never less.
     */
    private fun collapseIdentityOf(element: JsonNode): String {
        val component = element.path("component").deepCopy<JsonNode>()
        (component as? ObjectNode)?.remove("displayName")
        return listOf(
            element.path("versionRange").toString(),
            component.toString(),
            element.path("vcsSettings").toString(),
        ).joinToString("|")
    }

    private fun pairingKeyOf(element: JsonNode): Pair<String, String> = fullIdentityOf(element) to element.path("componentName").asText()

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
        return Verdict.Confirmed(extras.map { keyOf(it) }.toSet(), blindSpots(extras, base))
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

    /**
     * The baseline element this addition was collapsing into, under the real equality contract, and
     * what separated the two. A twin whose name differs from the addition's is NOT one: the old
     * `hashCode` read `displayName`, so those two hashed apart and never shared an element.
     */
    private fun twinOf(
        extra: JsonNode,
        baseline: JsonNode,
    ): Twin? {
        val identity = collapseIdentityOf(extra)
        val name = extra.path("componentName").asText()
        val extraDisplayName = displayNameOf(extra)
        return baseline
            .asSequence()
            .filter { collapseIdentityOf(it) == identity && it.path("componentName").asText() != name }
            .mapNotNull { twin ->
                val twinDisplayName = displayNameOf(twin)
                when {
                    sameName(twinDisplayName, extraDisplayName) -> Twin(twin, Cause.CONTRACT)
                    isBlankName(twinDisplayName) -> Twin(twin, Cause.ADR_021)
                    else -> null
                }
                // A nameless twin and a same-named one can both sit in the baseline — the old hash
                // told them apart. The same-named one is the true collapse partner, so prefer it.
            }.minByOrNull { it.cause.ordinal }
    }

    /** Equal as the old `hashCode` saw them: two blanks are the same bucket as two identical texts. */
    private fun sameName(
        left: String?,
        right: String?,
    ) = if (isBlankName(left)) isBlankName(right) else left == right

    /** What the old contract could not see: a recovered element whose distribution differs from its twin's. */
    private fun blindSpots(
        extras: List<JsonNode>,
        baseline: JsonNode,
    ): List<String> =
        extras.mapNotNull { extra ->
            val twin = twinOf(extra, baseline)
            if (twin != null && twin.element.path("distribution") != extra.path("distribution")) {
                "${keyOf(extra)} carries a distribution its baseline twin did not " +
                    "(twin componentName=${twin.element.path("componentName").asText()}) — the old contract was blind to it"
            } else {
                null
            }
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
        // A twin shows the element COULD have collapsed, never that the component EXISTED: copy the
        // matching fields off a real element, give it a name, and the story fits perfectly. The
        // baseline's own component inventory is the independent evidence — the collapse hides an
        // element from THIS Set and from nothing else, so a recovered component is still listed by
        // /components, and an invented one is not. Unverifiable is not verified: no inventory, no
        // suppression.
        when (BaselineInventory.knows(name)) {
            null ->
                return "the baseline component inventory is unavailable, so ${keyOf(extra)} cannot be verified"
            false ->
                return "${keyOf(extra)} is not a component of the baseline inventory — " +
                    "it was not recovered, it never existed"
            else -> Unit
        }
        val twin =
            twinOf(extra, baseline)
                ?: return "no baseline twin for ${keyOf(extra)} — the element was not collapsing under TD-022" +
                    " (${nearestMiss(extra, baseline)})"
        // Which of the two causes separated the pair decides where the recovery is allowed. The
        // repaired equality contract lives in the shared resolver-api, so it applies to BOTH
        // resolvers and refusing it in git-mode would refuse the fix's own effect. ADR-021 resolves a
        // name on the DB resolver alone, so a name appearing in git-mode is not a recovery — it is a
        // change that should not have happened, and the gate must keep saying so.
        return if (twin.cause == Cause.ADR_021 && Adr021DisplayName.gitMode) {
            "${keyOf(extra)} separated from a nameless twin by a display name, in git-mode where ADR-021 does not apply"
        } else {
            null
        }
    }
}
