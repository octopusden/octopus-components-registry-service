package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Canonicalises a **version-range-keyed JSON object** (a map whose KEYS are version-range expressions
 * and whose VALUES are the per-range payload) so that the ADR-018 decoupled-model enumeration compares
 * equal to V1 when the two describe the SAME piecewise (version → value) function but partition it
 * differently. It is applied to BOTH stands' bodies before [JsonShape.diff], exactly like
 * [RawArraySorters] does for Set-shape arrays — same normalisation on both sides, so equal functions
 * collapse to byte-identical maps and a genuine difference still surfaces.
 *
 * Four behaviour-preserving reshapings are folded out (all confirmed against the stand diff as
 * carrying zero coverage/value change):
 *  1. **Whitespace** — V1 keeps the verbatim DSL range string (`(, 2.0.2335)`, even newlines/indent);
 *     the v3 read path re-renders canonical (`(,2.0.2335)`).
 *  2. **Composite-split** — V1 keeps a multi-segment block as ONE key (`[a,b),[c,d)`); the partition
 *     emits one key per atomic sub-range. Exploding both to atomic intervals equates them.
 *  3. **Redundant-collapse / adjacent-merge** — V1 emits two contiguous same-value blocks
 *     (`[1.3,1.6)` + `[1.6,1.7)`); the partition merges them (`[1.3,1.7)`). Merging adjacent
 *     same-value intervals on BOTH sides equates them.
 *  4. **Version-form canonicalisation** — `[1,2.0)` ≡ `[1,2)`, `1.3` ≡ `1.3.0`, dash/dot
 *     `0.7-157` ≡ `0.7.157`. A scheme-agnostic numeric tokenisation renders a single canonical form.
 *
 * SAFETY: the merge step merges two intervals ONLY when their value nodes are byte-identical
 * ([JsonNode.equals]) AND they are version-contiguous — so it can never collapse a real per-range
 * value difference (different configs keep their own keys) nor bridge a genuine coverage gap (two
 * boundary-excluding neighbours do not merge). If ANY key fails to parse as a version range, the
 * object is returned UNCHANGED — the canonicaliser never silently drops or invents coverage.
 */
object VersionRangeMapCanonicalizer {
    /**
     * Apply [canonicalize] to the version-range-keyed object(s) carried by [root] for the given
     * [endpoint], returning a normalised copy (or [root] unchanged for endpoints with no such map).
     * Mirrors [RawArraySorters.stableSorted]: a narrow, opt-in per-endpoint registry — no implicit
     * auto-detection — applied to BOTH stands before [JsonShape.diff].
     *
     * Two registered shapes:
     *  - `…/maven-artifacts`: the ROOT object is keyed by version range → canonicalise the root.
     *  - `…/3/components` (list): each element carries a `variants` object keyed by version range →
     *    canonicalise each element's `variants` in place.
     */
    fun normalizeForEndpoint(endpoint: String, root: JsonNode?): JsonNode? {
        if (root == null) return null
        return when {
            endpoint.contains("maven-artifacts") ->
                (root as? ObjectNode)?.let { canonicalize(it) } ?: root
            // Exact suffix: ONLY the v3 list endpoint (`…/api/3/components`) carries the `variants` map.
            // Per-component v3 paths (`…/api/3/components/{c}/build-tools`) and the find-by-* actions do
            // not end here, so they are left untouched even though they share the prefix.
            endpoint.endsWith("/api/3/components") ->
                canonicalizeVariantsInArray(root)
            // /jira-component-version-ranges: Set<{componentName, versionRange, component, ...}> — the
            // versionRange is a FIELD, and the decoupled-model re-partition changes the element COUNT.
            endpoint.contains("jira-component-version-ranges") ->
                canonicalizeRangeKeyedArray(root)
            else -> root
        }
    }

    /**
     * Canonicalise a Set-shape array of objects each carrying a `versionRange` field (the
     * /jira-component-version-ranges shape). Group by `componentName`, treat each element's payload MINUS
     * `versionRange` as the value, merge adjacent same-value ranges per component (reusing [canonicalize]),
     * and re-emit — so V1's verbatim-block granularity and the candidate's partition granularity collapse
     * to the same set. Deterministically ordered for the positional [JsonShape.diff]. Returns the array
     * unchanged on any unexpected shape (non-object element, missing versionRange, or a duplicate range
     * within one component) so it can never silently drop an element.
     */
    fun canonicalizeRangeKeyedArray(root: JsonNode): JsonNode {
        val arr = root as? com.fasterxml.jackson.databind.node.ArrayNode ?: return root
        val byComponent = LinkedHashMap<String, LinkedHashMap<String, JsonNode>>() // component → (range → payload-without-range)
        for (el in arr) {
            val o = el as? ObjectNode ?: return root
            val comp = o.path("componentName").asText("")
            val range = o.get("versionRange")?.asText() ?: return root
            val payload = o.deepCopy().also { it.remove("versionRange") }
            val perComp = byComponent.getOrPut(comp) { LinkedHashMap() }
            if (perComp.containsKey(range)) return root // duplicate range within a component → don't risk a drop
            perComp[range] = payload
        }
        val emitted = mutableListOf<ObjectNode>()
        for ((_, rangeToPayload) in byComponent) {
            val mapNode = JsonNodeFactory.instance.objectNode()
            rangeToPayload.forEach { (range, payload) -> mapNode.set<JsonNode>(range, payload) }
            val canon = canonicalize(mapNode)
            val it = canon.fields()
            while (it.hasNext()) {
                val (mergedRange, payload) = it.next()
                val el = (payload as ObjectNode).deepCopy()
                el.put("versionRange", mergedRange)
                emitted += el
            }
        }
        emitted.sortBy { it.path("componentName").asText("") + "\u0000" + it.path("versionRange").asText("") }
        val out = JsonNodeFactory.instance.arrayNode(emitted.size)
        emitted.forEach { out.add(it) }
        return out
    }

    /**
     * Typed counterpart of [canonicalizeRangeKeyedArray] for the /jira-component-version-ranges compareDto
     * path: a List of objects each with a `versionRange` field. Serialise → [canonicalizeRangeKeyedArray]
     * (merge adjacent same-payload ranges per component) → deserialise back to [valueType], so the typed
     * recursive compareDto (with its gav/etc. normalisers) compares the merged set. SAFE to round-trip
     * here ONLY because the jira DTOs are immutable data classes with explicit @JsonProperty names +
     * @JsonCreator (faithful serialise/deserialise — unlike EscrowBean's `is`-prefixed boolean). On any
     * failure returns the list unchanged.
     */
    fun <T : Any> canonicalizeTypedRangeArray(list: List<T>, valueType: Class<T>): List<T> {
        if (list.isEmpty()) return list
        return runCatching {
            val arr = mapper.valueToTree<JsonNode>(list) as? com.fasterxml.jackson.databind.node.ArrayNode ?: return list
            val canon = canonicalizeRangeKeyedArray(arr) as? com.fasterxml.jackson.databind.node.ArrayNode ?: return list
            if (canon === arr) return list
            canon.map { mapper.treeToValue(it, valueType) }
        }.getOrDefault(list)
    }

    /** For a `/v3/components` array, return a copy where each element's `variants` object is canonicalised. */
    private fun canonicalizeVariantsInArray(root: JsonNode): JsonNode {
        val arr = root as? com.fasterxml.jackson.databind.node.ArrayNode ?: return root
        val out = JsonNodeFactory.instance.arrayNode(arr.size())
        for (el in arr) {
            val obj = el as? ObjectNode
            val variants = obj?.get("variants") as? ObjectNode
            if (obj == null || variants == null) {
                out.add(el)
            } else {
                val copy = JsonNodeFactory.instance.objectNode()
                copy.setAll<ObjectNode>(obj)
                copy.set<JsonNode>("variants", canonicalize(variants))
                out.add(copy)
            }
        }
        return out
    }

    // Kotlin-aware AND all datatype modules registered: the typed values are Kotlin classes with no
    // default constructor (need KotlinModule) and carry `Optional<…>` escrow fields (need
    // jackson-datatype-jdk8). Without findAndRegisterModules the round-trip throws on the first Optional
    // field and silently no-ops (runCatching → passthrough), leaving the reshaping un-canonicalised.
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        .registerModule(com.fasterxml.jackson.datatype.jdk8.Jdk8Module())

    /**
     * Canonicalise a TYPED version-range-keyed map (e.g. /maven-artifacts'
     * `Map<String, ComponentArtifactConfigurationDTO>` or `ComponentV3.variants`): normalise the KEYS
     * (whitespace / composite-split / adjacent-merge / version-form) while keeping the ORIGINAL typed
     * values untouched, so the caller's recursive `compareDto` still compares the values with its full
     * semantics (`ignoringCollectionOrder`, gav/artifactPattern normalisers) — only the range-key
     * reshaping is folded out. We serialise each value to JSON ONLY to decide merge-equality of adjacent
     * ranges (byte-identical value ⇒ mergeable); we DO NOT deserialise — the API beans round-trip
     * unfaithfully (Optional fields, and EscrowBean's `is`-prefixed boolean serialises as `isReusable`
     * but deserialises as `reusable`), which previously made the whole thing silently no-op. Keeping the
     * original instances is also faithful: no field can be lost or masked on a round-trip.
     * On any failure (e.g. an unparseable key) returns [map] unchanged — never drops coverage.
     */
    fun <V> canonicalizeTypedRangeMap(map: Map<String, V>): Map<String, V> {
        if (map.isEmpty()) return map
        return runCatching {
            // (interval, serialised-value-for-merge-equality, ORIGINAL typed value)
            val entries = mutableListOf<Triple<Interval, JsonNode, V>>()
            for ((key, value) in map) {
                val ivs = parseRangeKey(key) ?: return map // unparseable key → leave whole map intact
                val json = mapper.valueToTree<JsonNode>(value)
                for (iv in ivs) entries += Triple(iv, json, value)
            }
            val sorted = entries.sortedWith(
                Comparator { a, b ->
                    val lc = compareBound(a.first.lo, b.first.lo)
                    if (lc != 0) lc else (if (b.first.loIncl) 1 else 0) - (if (a.first.loIncl) 1 else 0)
                },
            )
            val merged = mutableListOf<Triple<Interval, JsonNode, V>>()
            for (cur in sorted) {
                val last = merged.lastOrNull()
                if (last != null && last.second == cur.second && contiguous(last.first, cur.first)) {
                    val unionHi = pickUpper(last.first, cur.first)
                    merged[merged.size - 1] =
                        Triple(Interval(last.first.lo, last.first.loIncl, unionHi.first, unionHi.second), last.second, last.third)
                } else {
                    merged += cur
                }
            }
            val out = LinkedHashMap<String, V>()
            val seenJson = HashMap<String, JsonNode>()
            for ((iv, json, value) in merged) {
                val k = renderInterval(iv)
                val prev = seenJson[k]
                // Two distinct keys that render to the same canonical range but carry DIFFERENT values
                // (a version-equal-but-differently-spelled collision) must NOT silently overwrite — bail
                // to passthrough so the difference still surfaces.
                if (prev != null && prev != json) return map
                seenJson[k] = json
                out[k] = value
            }
            out
        }.getOrDefault(map)
    }

    /**
     * Return a canonicalised copy of [map] (a version-range-keyed object). On any parse failure the
     * input is returned unchanged so an unexpected key shape surfaces as a real diff rather than noise.
     */
    fun canonicalize(map: ObjectNode): ObjectNode {
        val entries = mutableListOf<Pair<Interval, JsonNode>>()
        val it = map.fields()
        while (it.hasNext()) {
            val (key, value) = it.next()
            val intervals = parseRangeKey(key) ?: return map // unparseable → leave the whole object intact
            for (iv in intervals) entries += iv to value
        }
        if (entries.isEmpty()) return map

        // Sort by lower bound (–∞ first via compareBound), then inclusive-before-exclusive at the same
        // point, so adjacency between consecutive intervals is a local check.
        val sorted = entries.sortedWith(
            Comparator { a, b ->
                val lc = compareBound(a.first.lo, b.first.lo)
                if (lc != 0) lc else (if (b.first.loIncl) 1 else 0) - (if (a.first.loIncl) 1 else 0)
            },
        )

        // Merge adjacent same-value contiguous intervals into maximal runs.
        val merged = mutableListOf<Pair<Interval, JsonNode>>()
        for (cur in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.second == cur.second && contiguous(last.first, cur.first)) {
                val unionHi = pickUpper(last.first, cur.first)
                merged[merged.size - 1] = Interval(last.first.lo, last.first.loIncl, unionHi.first, unionHi.second) to last.second
            } else {
                merged += cur
            }
        }

        val out = JsonNodeFactory.instance.objectNode()
        for ((iv, value) in merged) {
            val k = renderInterval(iv)
            // Canonical-key collision with a DIFFERENT value (version-equal-but-differently-spelled input
            // keys) must not silently overwrite — bail to the original so the difference still surfaces.
            val prev = out.get(k)
            if (prev != null && prev != value) return map
            out.set<JsonNode>(k, value)
        }
        return out
    }

    // ── interval model ────────────────────────────────────────────────────────────

    /** Half-open/closed interval over version space; null bound = unbounded (±∞). */
    data class Interval(val lo: String?, val loIncl: Boolean, val hi: String?, val hiIncl: Boolean)

    /**
     * Parse a (possibly composite) version-range KEY into its atomic intervals. Returns null on any
     * malformed segment so the caller can leave the object untouched.
     */
    fun parseRangeKey(key: String): List<Interval>? {
        val stripped = key.replace(Regex("\\s+"), "")
        if (stripped.isEmpty()) return null
        val segments = splitComposite(stripped)
        val out = mutableListOf<Interval>()
        for (seg in segments) {
            out += parseSegment(seg) ?: return null
        }
        return out
    }

    /** Split a whitespace-stripped composite on the commas that sit BETWEEN segments (`)` or `]` then `[` or `(`). */
    private fun splitComposite(s: String): List<String> =
        s.split(Regex("(?<=[\\])]),(?=[\\[(])"))

    private fun parseSegment(seg: String): Interval? {
        // Single hard version: [x]
        Regex("^\\[([^,\\[\\]()]+)\\]$").matchEntire(seg)?.let { m ->
            val v = m.groupValues[1]
            return Interval(v, true, v, true)
        }
        // Bounded/half-bounded: <[(> lo , hi <])>  (either bound may be empty = ±∞)
        val m = Regex("^([\\[(])([^,\\[\\]()]*),([^,\\[\\]()]*)([\\])])$").matchEntire(seg) ?: return null
        val loIncl = m.groupValues[1] == "["
        val lo = m.groupValues[2].ifEmpty { null }
        val hi = m.groupValues[3].ifEmpty { null }
        val hiIncl = m.groupValues[4] == "]"
        return Interval(lo, loIncl, hi, hiIncl)
    }

    private fun renderInterval(iv: Interval): String {
        if (iv.lo == null && iv.hi == null) return "(,)"
        if (iv.lo != null && iv.hi != null && iv.loIncl && iv.hiIncl && compareVersion(iv.lo, iv.hi) == 0) {
            return "[${canonicalVersion(iv.lo)}]"
        }
        val sb = StringBuilder()
        sb.append(if (iv.loIncl) '[' else '(')
        sb.append(iv.lo?.let { canonicalVersion(it) } ?: "")
        sb.append(',')
        sb.append(iv.hi?.let { canonicalVersion(it) } ?: "")
        sb.append(if (iv.hiIncl) ']' else ')')
        return sb.toString()
    }

    // ── contiguity / merge helpers ──────────────────────────────────────────────────

    /**
     * True iff [a] (sorted no-later than [b]) is version-contiguous with [b] — they touch with no gap
     * and no version strictly between. Touch at point p when a.hi ≡ b.lo and at least one side includes
     * p (so p is covered), OR they overlap. Two boundary-EXCLUDING neighbours (a.hi ≡ b.lo, both
     * exclusive) do NOT merge — that is a genuine one-point gap.
     */
    private fun contiguous(a: Interval, b: Interval): Boolean {
        if (a.hi == null) return true // a runs to +∞ → covers b's start
        if (b.lo == null) return true // b runs from –∞ (shouldn't happen post-sort, but safe)
        val cmp = compareVersion(a.hi, b.lo)
        if (cmp > 0) return true // overlap
        if (cmp == 0) return a.hiIncl || b.loIncl // touch: covered iff at least one includes the point
        return false // gap
    }

    /** The farther-right upper bound of [a] and [b] (handles +∞ and inclusive-vs-exclusive at the same point). */
    private fun pickUpper(a: Interval, b: Interval): Pair<String?, Boolean> {
        if (a.hi == null || b.hi == null) return null to false
        val cmp = compareVersion(a.hi, b.hi)
        return when {
            cmp > 0 -> a.hi to a.hiIncl
            cmp < 0 -> b.hi to b.hiIncl
            else -> a.hi to (a.hiIncl || b.hiIncl)
        }
    }

    private fun compareBound(a: String?, b: String?): Int =
        when {
            a == null && b == null -> 0
            a == null -> -1
            b == null -> 1
            else -> compareVersion(a, b)
        }

    // ── scheme-agnostic version tokenisation ────────────────────────────────────────

    /**
     * Compare two version strings numerically and scheme-agnostically: split on `.` and `-`, compare
     * token-by-token (numeric where both tokens are integers, else lexical), with a missing trailing
     * token treated as 0 — so `2` ≡ `2.0`, `1.3` ≡ `1.3.0`, and `0.7-157` ≡ `0.7.157`.
     */
    fun compareVersion(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = maxOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta.getOrNull(i) ?: ZERO
            val y = tb.getOrNull(i) ?: ZERO
            val c = compareToken(x, y)
            if (c != 0) return c
        }
        return 0
    }

    /** Canonical string for a version: numeric tokens re-joined with `.`, trailing zero tokens trimmed
     *  (but never below one token), so equal versions render identically regardless of `.`/`-` or trailing `.0`. */
    private fun canonicalVersion(v: String): String {
        val toks = tokenize(v).toMutableList()
        while (toks.size > 1 && toks.last().let { it.num != null && it.num == 0L }) {
            toks.removeAt(toks.size - 1)
        }
        return toks.joinToString(".") { it.raw }
    }

    private data class Token(val raw: String, val num: Long?)

    private val ZERO = Token("0", 0L)

    private fun tokenize(v: String): List<Token> =
        v.split('.', '-').filter { it.isNotEmpty() }.map { part ->
            Token(part, part.toLongOrNull())
        }

    private fun compareToken(a: Token, b: Token): Int =
        when {
            a.num != null && b.num != null -> a.num.compareTo(b.num)
            a.num != null -> -1 // numeric sorts before alpha (qualifier)
            b.num != null -> 1
            else -> a.raw.compareTo(b.raw)
        }
}
