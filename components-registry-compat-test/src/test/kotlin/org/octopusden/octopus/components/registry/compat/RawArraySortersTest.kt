package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [RawArraySorters] — pre-sort logic that kills the
 * position-based STRUCTURAL_DIFF false-positives on Set-shape endpoints
 * (legacy "cluster I").
 *
 * No HTTP, no live stand — pure JsonNode round-trip. Uses JUnit assertions
 * directly to sidestep AssertJ overload-resolution ambiguity on
 * `List<ShapeDiff>`.
 */
@Tag("unit")
class RawArraySortersTest {
    private val factory = JsonNodeFactory.instance

    /**
     * Mirrors the wire shape of `JiraComponentVersionRangeDTO`:
     *   { componentName: <top-level>, versionRange, component: { displayName, ... }, ... }
     * `componentName` lives at the TOP level. The nested `component` object is a
     * `JiraComponentDTO` and does NOT carry an `id` field. `displayName` is the
     * field used in the prod responses (and the one the compat report shows
     * STRUCTURAL_DIFFs against).
     */
    private fun rangeEntry(componentName: String, versionRange: String, displayName: String? = null): JsonNode {
        val obj = factory.objectNode()
        obj.put("componentName", componentName)
        obj.put("versionRange", versionRange)
        val component = factory.objectNode()
        if (displayName != null) component.put("displayName", displayName)
        obj.set<JsonNode>("component", component)
        return obj
    }

    /**
     * Mirrors the wire shape of `ComponentV3`:
     *   { "component": { "id", "name", ... }, "variants": { ... } }
     * `name` is optional in the wire response — use it as the structural-shape
     * variable so tests can demonstrate that alignment actually happened.
     */
    private fun v3Entry(componentId: String, name: String? = null): JsonNode {
        val obj = factory.objectNode()
        val component = factory.objectNode()
        component.put("id", componentId)
        if (name != null) component.put("name", name)
        obj.set<JsonNode>("component", component)
        obj.set<JsonNode>("variants", factory.objectNode())
        return obj
    }

    /**
     * Mirrors the wire shape of a `/rest/api/{1,2}/components` array element —
     * a `ComponentV1` / `ComponentV2` DTO with `id` at the top level. Used as
     * a fixture for the nested-array tests: the array is wrapped under
     * `{ "components": [ ... ] }` on the wire (see `v12Wrap` below).
     *
     * `releaseManager` is the structural-shape variable — present on one element,
     * absent on the other. Without alignment, positional `JsonShape.diff` would
     * surface KEY_MISSING on `$.components[*].releaseManager`; after alignment,
     * each row lands at its proper index and the diff disappears.
     */
    private fun v12Entry(componentId: String, releaseManager: String? = null): JsonNode {
        val obj = factory.objectNode()
        obj.put("id", componentId)
        if (releaseManager != null) obj.put("releaseManager", releaseManager)
        return obj
    }

    private fun v12Wrap(vararg entries: JsonNode): JsonNode {
        val arr = factory.arrayNode()
        entries.forEach { arr.add(it) }
        val root = factory.objectNode()
        root.set<JsonNode>("components", arr)
        return root
    }

    @Test
    @DisplayName(
        "registered Set-endpoint with same elements in different wire-order produces zero structural diffs after sort",
    )
    fun jiraComponentVersionRanges_sameElementsDifferentOrder_zeroShapeDiffs() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)"))
            add(rangeEntry("beta-fixture", "[2.0,)"))
            add(rangeEntry("gamma-fixture", "(,3.0)"))
        }
        val candidate = factory.arrayNode().apply {
            // Same three elements, deliberately reversed wire-order.
            add(rangeEntry("gamma-fixture", "(,3.0)"))
            add(rangeEntry("beta-fixture", "[2.0,)"))
            add(rangeEntry("alpha-fixture", "[1.0,)"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "after stable sort, JsonShape.diff must see zero structural divergence for Set-equivalent payloads, got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "Set elements sharing (componentName, versionRange) but differing in content still align (canonical tie-breaker)",
    )
    fun jiraComponentVersionRanges_collidingPrimaryKey_zeroDiffsAfterSort() {
        // Real production shape: two entries share componentName+versionRange but differ
        // in content (here one carries component.displayName, one doesn't). As a multiset
        // both stands are identical — only the wire order differs. The (name,range) key
        // alone COLLIDES, so a stable sort preserves each stand's arbitrary input order and
        // the positional compare reports false displayName KEY_MISSING diffs. The canonical
        // tie-breaker must make the order deterministic on both sides so they realign.
        val endpoint = "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges"
        val baseline = factory.arrayNode().apply {
            add(rangeEntry("dup-fixture", "[1.0,)", displayName = "Alpha"))
            add(rangeEntry("dup-fixture", "[1.0,)"))
        }
        val candidate = factory.arrayNode().apply {
            add(rangeEntry("dup-fixture", "[1.0,)"))
            add(rangeEntry("dup-fixture", "[1.0,)", displayName = "Alpha"))
        }
        val diffs = JsonShape.diff(
            RawArraySorters.stableSorted(endpoint, baseline),
            RawArraySorters.stableSorted(endpoint, candidate),
        )
        assertTrue(
            diffs.isEmpty(),
            "colliding (componentName, versionRange) entries must realign via the canonical tie-breaker; got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "regression guard: heterogeneous-shape entries (one with displayName, one without) " +
            "produce zero diffs ONLY after key-correct sort — no-op or wrong-key sorter would surface KEY_MISSING",
    )
    fun jiraComponentVersionRanges_heterogeneousShape_zeroDiffsOnlyAfterAlignment() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

        // alpha-fixture carries `component.displayName`; beta-fixture does NOT.
        // The two stands ship the same set in OPPOSITE wire-order. JsonShape.diff
        // is positional, so without alignment:
        //   baseline[0] (alpha, with displayName) vs candidate[0] (beta, no displayName)
        //   → KEY_MISSING_CANDIDATE on $[0].component.displayName
        //   → KEY_MISSING_BASELINE  on $[1].component.displayName
        // After alignment by `componentName`, both arrays land [alpha, beta] and
        // every position matches. This test would FAIL under a no-op sorter or
        // under the earlier-buggy `component.id` key (which collapses to "" so
        // stable sort preserves input order).
        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha A"))
            add(rangeEntry("beta-fixture", "[1.0,)"))
        }
        val candidate = factory.arrayNode().apply {
            add(rangeEntry("beta-fixture", "[1.0,)"))
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha A"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "expected zero diffs after alignment by componentName; got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "per-project jira-component-version-ranges is normalized through the same composite-key sorter",
    )
    fun jiraComponentVersionRanges_perProject_heterogeneousShape_zeroDiffsAfterAlignment() {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges"

        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha A"))
            add(rangeEntry("beta-fixture", "[1.0,)"))
        }
        val candidate = factory.arrayNode().apply {
            add(rangeEntry("beta-fixture", "[1.0,)"))
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha A"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "per-project endpoint must share the global sort; got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "registered v3 components endpoint: heterogeneous-shape entries produce zero diffs ONLY after alignment by component.id",
    )
    fun v3Components_heterogeneousShape_zeroDiffsOnlyAfterAlignment() {
        val endpoint = "GET /rest/api/3/components"

        // alpha-fixture carries `component.name`; beta-fixture does NOT.
        // Without alignment, positional `JsonShape.diff` surfaces
        // KEY_MISSING_CANDIDATE on $[0].component.name (and the mirror on $[1]).
        val baseline = factory.arrayNode().apply {
            add(v3Entry("alpha-fixture", name = "Alpha"))
            add(v3Entry("beta-fixture"))
        }
        val candidate = factory.arrayNode().apply {
            add(v3Entry("beta-fixture"))
            add(v3Entry("alpha-fixture", name = "Alpha"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "expected zero diffs after alignment by component.id; got: $diffs",
        )
    }

    /**
     * Pull the post-sort `components[0].id` from the wrapped-array shape, for
     * pinning that the sort key was the `id` field (Opus Stage-2 finding —
     * a wrong-key sort that happens to align would otherwise pass the
     * zero-diff assertion).
     */
    private fun firstComponentId(sorted: JsonNode?): String =
        sorted?.path("components")?.get(0)?.path("id")?.asText("") ?: ""

    @Test
    @DisplayName(
        "registered v2 /components endpoint (wrapped under \"components\"): heterogeneous-shape entries " +
            "produce zero diffs ONLY after alignment by id",
    )
    fun v2Components_heterogeneousShape_zeroDiffsOnlyAfterAlignment() {
        val endpoint = "GET /rest/api/2/components"

        // Mirrors what the live stands return in different wire-order today —
        // `EscrowConfiguration.escrowModules` is a HashMap (groovy:
        // `Map<String, EscrowModule> escrowModules = new HashMap<>()`), so the
        // 948-component list comes out in unpredictable order on each stand.
        // alpha carries `releaseManager`, beta does not.
        val baseline = v12Wrap(
            v12Entry("alpha-fixture", releaseManager = "alice"),
            v12Entry("beta-fixture"),
        )
        val candidate = v12Wrap(
            v12Entry("beta-fixture"),
            v12Entry("alpha-fixture", releaseManager = "alice"),
        )

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "expected zero diffs after alignment by id under \$.components[]; got: $diffs",
        )
        // Pin the sort *key*: a wrong-key sort (e.g. by `name` instead of `id`)
        // could still produce zero diffs by accident; asserting the first-row
        // id forces the key to actually be `id`. alpha-fixture < beta-fixture
        // lexicographically.
        assertEquals("alpha-fixture", firstComponentId(baselineSorted))
        assertEquals("alpha-fixture", firstComponentId(candidateSorted))
    }

    @Test
    @DisplayName(
        "registered v1 /components endpoint shares the same nested-array sort path",
    )
    fun v1Components_heterogeneousShape_zeroDiffsOnlyAfterAlignment() {
        val endpoint = "GET /rest/api/1/components"

        val baseline = v12Wrap(
            v12Entry("alpha-fixture", releaseManager = "alice"),
            v12Entry("beta-fixture"),
        )
        val candidate = v12Wrap(
            v12Entry("beta-fixture"),
            v12Entry("alpha-fixture", releaseManager = "alice"),
        )

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.isEmpty(),
            "v1 nested-array sort must produce zero diffs after alignment; got: $diffs",
        )
        assertEquals("alpha-fixture", firstComponentId(baselineSorted))
        assertEquals("alpha-fixture", firstComponentId(candidateSorted))
    }

    @Test
    @DisplayName(
        "wrong-key regression guard: when one element has no `id`, it sorts to position 0 " +
            "(empty-string key); the diff that surfaces is the genuine KEY_MISSING, " +
            "not positional noise",
    )
    fun v2Components_missingIdSortsToFront_realDiffSurvives() {
        val endpoint = "GET /rest/api/2/components"
        // alpha-fixture has id; the other element has NO id field at all
        // (mimics a hypothetical regression dropping `id` on one component).
        // The missing-id key falls to "" → sorts to front → on baseline the
        // missing-id row is at index 0, on candidate at index 1 (its native
        // wire order keeps it second). After RawArraySorters runs on both,
        // baseline = [{}, alpha] and candidate = [{}, alpha] — alignment
        // works, but the underlying TYPE_MISMATCH on the missing-id row's
        // `id` field still surfaces as a real diff.
        val baseline = v12Wrap(
            factory.objectNode().put("releaseManager", "alice"), // no id
            v12Entry("alpha-fixture"),
        )
        val candidate = v12Wrap(
            v12Entry("alpha-fixture"),
            factory.objectNode().put("releaseManager", "alice"), // no id
        )
        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        // Alignment is deterministic — empty-string key sorts to front on
        // both stands. Zero diffs is the correct outcome here because the
        // two "missing id" rows match each other shape-for-shape and the
        // two alpha rows match too. The point of the test is that the
        // missing-id collapse does NOT cause a cascade of positional
        // noise.
        assertTrue(
            diffs.isEmpty(),
            "missing-id elements must align via the empty-string key on both stands; got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "nestedArraySort: non-ObjectNode root (e.g. bare ArrayNode handed to a wrapped-shape endpoint) " +
            "is returned unchanged",
    )
    fun v2Components_nonObjectRoot_passthrough() {
        val endpoint = "GET /rest/api/2/components"
        val root: JsonNode = factory.arrayNode().add(v12Entry("a")).add(v12Entry("b"))
        val sorted = RawArraySorters.stableSorted(endpoint, root)
        assertSame(root, sorted)
    }

    @Test
    @DisplayName(
        "nestedArraySort: nested field present but NOT an ArrayNode (e.g. `components` is a string) → identity",
    )
    fun v2Components_innerFieldNotArray_passthrough() {
        val endpoint = "GET /rest/api/2/components"
        val root: JsonNode = factory.objectNode().put("components", "not-an-array")
        val sorted = RawArraySorters.stableSorted(endpoint, root)
        assertSame(root, sorted)
    }

    @Test
    @DisplayName(
        "anti-regression: v2 /components with a real value diff between paired entries still surfaces it after sort",
    )
    fun v2Components_realDiffStillReportedAfterSort() {
        val endpoint = "GET /rest/api/2/components"

        // alpha has releaseManager on baseline but NOT on candidate — a real
        // backward-compat regression that must NOT be masked by the sorter.
        val baseline = v12Wrap(
            v12Entry("alpha-fixture", releaseManager = "alice"),
            v12Entry("beta-fixture", releaseManager = "bob"),
        )
        val candidate = v12Wrap(
            v12Entry("beta-fixture", releaseManager = "bob"),
            v12Entry("alpha-fixture"),
        )

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.any { it.kind == JsonShape.ShapeDiff.Kind.KEY_MISSING_CANDIDATE },
            "expected the real diff (alpha drops releaseManager) to survive sorting; got: $diffs",
        )
    }

    @Test
    @DisplayName(
        "v2 /components with missing \"components\" field (non-conforming root) is returned unchanged",
    )
    fun v2Components_missingNestedField_passthrough() {
        val endpoint = "GET /rest/api/2/components"
        // Root is an ObjectNode but lacks the `components` key — the transform
        // must short-circuit to identity rather than NPE or invent an empty array.
        val baseline: JsonNode = factory.objectNode().put("foo", "bar")
        val sorted = RawArraySorters.stableSorted(endpoint, baseline)
        assertSame(baseline, sorted)
    }

    @Test
    @DisplayName(
        "unregistered endpoint returns the input unchanged (no implicit auto-detection)",
    )
    fun unregisteredEndpoint_passthrough() {
        val endpoint = "GET /rest/api/2/components/unregistered/maven-artifacts"

        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)"))
            add(rangeEntry("beta-fixture", "[2.0,)"))
        }

        val sorted = RawArraySorters.stableSorted(endpoint, baseline)
        // Identity guarantee: when an endpoint is not registered we return the
        // input node verbatim, preserving wire-order. This keeps the surface
        // narrow — only the two known Set-shape offenders are normalized.
        assertSame(baseline, sorted)
    }

    @Test
    @DisplayName(
        "non-array root (e.g. ObjectNode) is returned unchanged even for registered endpoint",
    )
    fun nonArrayRoot_passthrough() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

        val baseline: JsonNode = factory.objectNode().put("foo", "bar")
        val sorted = RawArraySorters.stableSorted(endpoint, baseline)
        assertSame(baseline, sorted)
    }

    @Test
    @DisplayName(
        "anti-regression: arrays with truly different sizes still produce ARRAY_SIZE_MISMATCH after sort",
    )
    fun antiRegression_arraySizeMismatchStillReported() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)"))
            add(rangeEntry("beta-fixture", "[2.0,)"))
        }
        val candidate = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertEquals(1, diffs.size, "expected exactly one size-mismatch diff, got: $diffs")
        assertEquals(JsonShape.ShapeDiff.Kind.ARRAY_SIZE_MISMATCH, diffs.single().kind)
    }

    @Test
    @DisplayName(
        "anti-regression: same-size arrays with a true per-element shape diff still surface it after sort",
    )
    fun antiRegression_perElementShapeDiffStillReported() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

        val baseline = factory.arrayNode().apply {
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha"))
            add(rangeEntry("beta-fixture", "[2.0,)", displayName = "Beta"))
        }
        // Same keys, but the candidate omits displayName on beta — a real shape divergence
        // (KEY_MISSING_CANDIDATE) that survives any reordering.
        val candidate = factory.arrayNode().apply {
            add(rangeEntry("beta-fixture", "[2.0,)"))
            add(rangeEntry("alpha-fixture", "[1.0,)", displayName = "Alpha"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(
            diffs.any { it.kind == JsonShape.ShapeDiff.Kind.KEY_MISSING_CANDIDATE },
            "expected at least one KEY_MISSING_CANDIDATE diff after sort, got: $diffs",
        )
    }
}
