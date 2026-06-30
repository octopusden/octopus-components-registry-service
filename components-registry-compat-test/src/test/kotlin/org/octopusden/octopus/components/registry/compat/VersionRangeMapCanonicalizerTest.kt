package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VersionRangeMapCanonicalizer]. The contract: two version-range-keyed objects that
 * describe the SAME piecewise (version → value) function canonicalise to byte-identical objects, while
 * any real coverage/value difference still produces distinct canonical objects. Equality of canonical
 * forms is what [JsonShape.diff] then sees, so these tests pin the suppress-reshaping / surface-real-diff
 * boundary directly.
 */
class VersionRangeMapCanonicalizerTest {
    private val mapper = ObjectMapper()

    private fun obj(vararg pairs: Pair<String, String>): ObjectNode {
        val o = mapper.createObjectNode()
        for ((k, v) in pairs) o.set<com.fasterxml.jackson.databind.JsonNode>(k, mapper.readTree(v))
        return o
    }

    private fun canon(o: ObjectNode) = VersionRangeMapCanonicalizer.canonicalize(o)

    // ── (1) whitespace ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("whitespace in range keys is normalised away (`(, X)` ≡ `(,X)`)")
    fun whitespace() {
        val a = obj("(, 2.0.2335)" to """{"x":1}""", "[2.0.2335, )" to """{"x":2}""")
        val b = obj("(,2.0.2335)" to """{"x":1}""", "[2.0.2335,)" to """{"x":2}""")
        assertEquals(canon(b), canon(a))
    }

    // ── (2) composite-split ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("a composite key splits to the same atomic intervals as separate keys")
    fun compositeSplit() {
        // V1 keeps a two-segment block as one key; the partition emits two atomic keys. Same values.
        val v1 = obj("[1.0,2.0),[5.0,6.0)" to """{"x":1}""")
        val cand = obj("[1.0,2.0)" to """{"x":1}""", "[5.0,6.0)" to """{"x":1}""")
        assertEquals(canon(cand), canon(v1))
    }

    // ── (3) redundant-collapse / adjacent-merge ───────────────────────────────────────
    @Test
    @DisplayName("two contiguous same-value blocks merge to the single range the partition emits")
    fun adjacentMerge() {
        val v1 = obj("[1.3,1.6)" to """{"x":1}""", "[1.6,1.7)" to """{"x":1}""")
        val cand = obj("[1.3,1.7)" to """{"x":1}""")
        assertEquals(canon(cand), canon(v1))
    }

    @Test
    @DisplayName("singleton absorbed into an adjacent open range when same value (`(a,p)`+`[p]` ≡ `(a,p]`)")
    fun singletonAbsorption() {
        val v1 = obj("(2.207,2.313)" to """{"x":1}""", "[2.313]" to """{"x":1}""")
        val cand = obj("(2.207,2.313]" to """{"x":1}""")
        assertEquals(canon(cand), canon(v1))
    }

    // ── (4) version-form canonicalisation ─────────────────────────────────────────────
    @Test
    @DisplayName("trailing-zero / dash-vs-dot version forms are equated (`[1,2.0)`≡`[1,2)`, `0.7-157`≡`0.7.157`)")
    fun versionForm() {
        assertEquals(canon(obj("[1,2)" to """{"x":1}""")), canon(obj("[1,2.0)" to """{"x":1}""")))
        assertEquals(canon(obj("[1.0.83,1.3.0)" to """1""")), canon(obj("[1.0.83,1.3)" to """1""")))
        assertEquals(canon(obj("(0.7.157,0.7-803]" to """1""")), canon(obj("(0.7-157,0.7-803]" to """1""")))
    }

    @Test
    @DisplayName("single-version `[x]` renders identically regardless of trailing-zero form")
    fun singleVersionForm() {
        assertEquals(canon(obj("[11.7.1.0]" to """1""")), canon(obj("[11.7.1]" to """1""")))
        assertEquals(canon(obj("[2.0]" to """1""")), canon(obj("[2]" to """1""")))
    }

    @Test
    @DisplayName("overlapping same-value intervals (composite `[1,3),[2,5)`) merge to their union `[1,5)`")
    fun overlappingSameValueMerge() {
        assertEquals(canon(obj("[1,5)" to """{"x":1}""")), canon(obj("[1,3),[2,5)" to """{"x":1}""")))
    }

    @Test
    @DisplayName("value equality is field-order independent (`{x,y}` ≡ `{y,x}`) so such neighbours still merge")
    fun valueEqualityIgnoresFieldOrder() {
        val a = obj("[1,2)" to """{"x":1,"y":2}""", "[2,3)" to """{"y":2,"x":1}""")
        val b = obj("[1,3)" to """{"x":1,"y":2}""")
        assertEquals(canon(b), canon(a))
    }

    // ── SAFETY: real differences MUST still surface ────────────────────────────────────
    @Test
    @DisplayName("adjacent ranges with DIFFERENT values do NOT merge (a real per-range value change surfaces)")
    fun differentValuesDoNotMerge() {
        val a = obj("[1.3,1.6)" to """{"x":1}""", "[1.6,1.7)" to """{"x":2}""")
        val b = obj("[1.3,1.7)" to """{"x":1}""") // candidate wrongly merged across a value change
        assertNotEquals(canon(b), canon(a))
    }

    @Test
    @DisplayName("a genuine one-point gap (both neighbours exclude the point) is NOT bridged")
    fun genuineGapNotBridged() {
        // (,5.1.0] then [5.1.1,) — coverage holds a gap (5.1.0,5.1.1). Must not merge into (,).
        val withGap = obj("(,5.1.0]" to """1""", "[5.1.1,)" to """1""")
        val continuous = obj("(,)" to """1""")
        assertNotEquals(canon(continuous), canon(withGap))
    }

    @Test
    @DisplayName("a missing range on one side (coverage difference) surfaces as a distinct canonical map")
    fun coverageDifferenceSurfaces() {
        val a = obj("[1.0,2.0)" to """1""", "[2.0,3.0)" to """2""")
        val b = obj("[1.0,2.0)" to """1""") // candidate dropped [2.0,3.0)
        assertNotEquals(canon(b), canon(a))
    }

    // ── robustness ────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("an unparseable key leaves the whole object untouched (never silently drops coverage)")
    fun unparseablePassthrough() {
        val weird = obj("not-a-range" to """1""", "[1.0,2.0)" to """2""")
        assertEquals(weird, canon(weird))
    }

    @Test
    @DisplayName("ALL_VERSIONS composite `(,0),[0,)` with one value collapses to the `(,)` sentinel")
    fun allVersionsCollapses() {
        assertEquals(canon(obj("(,)" to """1""")), canon(obj("(,0),[0,)" to """1""")))
    }

    // ── endpoint wiring: normalizeForEndpoint → JsonShape.diff ──────────────────────────
    @Test
    @DisplayName("/maven-artifacts: reshaped root range-map → ZERO shape diffs; a coverage change still surfaces")
    fun mavenArtifactsRootWiring() {
        // JsonShape is STRUCTURAL (keys/types) — a pure value change inside a matched range is the
        // typed layer's job; the raw-layer safety property is that a COVERAGE/key difference surfaces.
        val ep = "GET /rest/api/2/components/comp-x/maven-artifacts"
        val v1 = obj("[1.0,2.0),[5.0,6.0)" to """{"a":1}""", "[2.0, 3.0)" to """{"a":2}""")
        val cand = obj("[1.0,2.0)" to """{"a":1}""", "[5.0,6.0)" to """{"a":1}""", "[2,3)" to """{"a":2}""")
        val bn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, v1)
        val cn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, cand)
        assertEquals(emptyList<JsonShape.ShapeDiff>(), JsonShape.diff(bn, cn))

        // Candidate drops the [5,6) coverage → a real KEY difference must surface.
        val candReal = obj("[1.0,2.0)" to """{"a":1}""", "[2,3)" to """{"a":2}""")
        val cnReal = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, candReal)
        assertNotEquals(emptyList<JsonShape.ShapeDiff>(), JsonShape.diff(bn, cnReal))
    }

    @Test
    @DisplayName("/v3/components: per-element `variants` reshaping → ZERO shape diffs (component scalars untouched)")
    fun v3VariantsWiring() {
        val ep = "GET /rest/api/3/components"
        val v1 = mapper.createArrayNode().apply {
            add(
                mapper.createObjectNode().apply {
                    set<JsonNode>("component", mapper.readTree("""{"id":"c1","componentOwner":"o"}"""))
                    set<JsonNode>("variants", obj("(, 2.0)" to """{"x":1}""", "[2.0,2.5)" to """{"x":1}""", "[2.5,)" to """{"x":2}"""))
                },
            )
        }
        val cand = mapper.createArrayNode().apply {
            add(
                mapper.createObjectNode().apply {
                    set<JsonNode>("component", mapper.readTree("""{"id":"c1","componentOwner":"o"}"""))
                    // Same coverage as v1, merged: (,2.0)+[2.0,2.5) (both x:1) → (,2.5); then [2.5,) x:2.
                    set<JsonNode>("variants", obj("(,2.5)" to """{"x":1}""", "[2.5,)" to """{"x":2}"""))
                },
            )
        }
        val bn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, v1)
        val cn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, cand)
        assertEquals(emptyList<JsonShape.ShapeDiff>(), JsonShape.diff(bn, cn))
    }

    // ── typed-layer map equality (compareDto `variants` field) ──────────────────────────
    @Test
    @DisplayName("canonicalizeTypedRangeMap preserves a value's collection ORDER verbatim (compareDto ignores it, not us)")
    fun typedRangeMapPreservesCollectionOrder() {
        data class Val(val ids: List<String> = emptyList())
        // Adjacent ranges with the SAME value merge; the surviving value's list order is preserved as-is
        // (NOT sorted) so the downstream recursive compareDto — not this canonicaliser — decides
        // collection-order equality. (Normalising order here would shift that decision to the wrong layer
        // and was the shape of the build-4073 regression.)
        val m = mapOf("[1,2)" to Val(listOf("b", "a")), "[2,3)" to Val(listOf("b", "a")))
        val canon = VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(m)
        assertEquals(setOf("[1,3)"), canon.keys)
        assertEquals(listOf("b", "a"), canon["[1,3)"]!!.ids)
    }

    @Test
    @DisplayName("canonicalizeTypedRangeMap: normalises keys + merges adjacent equal values, keeps values typed")
    fun typedRangeMapRoundTrip() {
        data class V(val pattern: String = "", val groupId: String = "")
        val m = mapOf(
            "[1.0,2.0)" to V("a", "g"),
            "[2.0, 3.0)" to V("a", "g"), // contiguous + equal → merges with the above
            "[5.0,6.0)" to V("b", "g"),
        )
        val canon = VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(m)
        assertEquals(setOf("[1,3)", "[5,6)"), canon.keys)
        assertEquals(V("a", "g"), canon["[1,3)"])
        assertEquals(V("b", "g"), canon["[5,6)"])

        // unparseable key → returned unchanged (never drops coverage)
        val weird = mapOf("not-a-range" to V("x"))
        assertEquals(weird, VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(weird))

        // Production DTO (the /maven-artifacts value type): values are kept as the ORIGINAL instances
        // (canonicaliser touches only keys), so no field is lost — merged keys, identical values.
        val real = mapOf(
            "[1.0,2.0)" to org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO("g", "a"),
            "[2.0,3.0)" to org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO("g", "a"),
            "[5.0,6.0)" to org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO("g2", "b"),
        )
        val rc = VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(real)
        assertEquals(setOf("[1,3)", "[5,6)"), rc.keys)
        assertEquals("g", rc["[1,3)"]!!.groupPattern)
        assertEquals("a", rc["[1,3)"]!!.artifactPattern)
        assertEquals("g2", rc["[5,6)"]!!.groupPattern)
    }

    @Test
    @DisplayName("canonicalizeTypedRangeMap handles the real VersionedComponentConfigurationBean (variants value)")
    fun typedRangeMapRoundTripsVariantBean() {
        fun cfg(group: String) =
            org.octopusden.octopus.components.registry.api.beans.VersionedComponentConfigurationBean().apply {
                setGroupId(group)
                setVcs(org.octopusden.octopus.components.registry.api.beans.GitVersionControlSystemBean("url", "tag1", "main"))
            }
        // The variant bean serialises Optional<…> escrow fields (needs the jdk8 module) and an
        // `is`-prefixed boolean that does NOT deserialise back symmetrically — so the canonicaliser must
        // NOT round-trip it. Two adjacent same-value ranges must MERGE (proves serialisation-for-equality
        // works) while the surviving value stays the ORIGINAL instance (groupId + vcs.tag intact).
        val m = mapOf("[1,2)" to cfg("g"), "[2,3)" to cfg("g"))
        val canon = VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(m)
        assertEquals(setOf("[1,3)"), canon.keys)
        assertEquals("g", canon["[1,3)"]!!.groupId)
        assertEquals(
            "tag1",
            (canon["[1,3)"]!!.vcs as org.octopusden.octopus.components.registry.api.vcs.GitVersionControlSystem).tag,
        )
    }

    @Test
    @DisplayName("/jira-component-version-ranges Set-array: reshaped per-component ranges → ZERO shape diffs; real payload change surfaces")
    fun jiraRangesSetArrayWiring() {
        val ep = "GET /rest/api/2/common/jira-component-version-ranges"
        fun el(range: String, project: String) =
            mapper.readTree("""{"componentName":"c","versionRange":"$range","component":{"projectKey":"$project"}}""")
        // V1 splits component c into two adjacent same-payload ranges; candidate merges → array size differs.
        val v1 = mapper.createArrayNode().apply { add(el("[1,2)", "P")); add(el("[2,3)", "P")) }
        val cand = mapper.createArrayNode().apply { add(el("[1,3)", "P")) }
        val bn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, v1)
        val cn = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, cand)
        assertEquals(emptyList<JsonShape.ShapeDiff>(), JsonShape.diff(bn, cn))

        // A real payload difference (different projectKey) must NOT be merged away → surfaces.
        val candReal = mapper.createArrayNode().apply { add(el("[1,2)", "P")); add(el("[2,3)", "Q")) }
        val cnReal = VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, candReal)
        assertNotEquals(emptyList<JsonShape.ShapeDiff>(), JsonShape.diff(bn, cnReal))
    }

    @Test
    @DisplayName("unregistered endpoint is passed through untouched (no accidental canonicalisation)")
    fun unregisteredEndpointPassthrough() {
        val ep = "GET /rest/api/2/components"
        val body = mapper.readTree("""{"components":[{"id":"x"}]}""")
        assertEquals(body, VersionRangeMapCanonicalizer.normalizeForEndpoint(ep, body))
    }
}
