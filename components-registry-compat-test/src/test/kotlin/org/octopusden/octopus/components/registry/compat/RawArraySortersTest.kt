package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
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
class RawArraySortersTest {
    private val factory = JsonNodeFactory.instance

    private fun rangeEntry(componentId: String, versionRange: String, displayName: String? = null): JsonNode {
        val obj = factory.objectNode()
        val component = factory.objectNode()
        component.put("id", componentId)
        if (displayName != null) component.put("displayName", displayName)
        obj.set<JsonNode>("component", component)
        obj.put("versionRange", versionRange)
        return obj
    }

    private fun v3Entry(componentId: String): JsonNode {
        val obj = factory.objectNode()
        val component = factory.objectNode()
        component.put("id", componentId)
        obj.set<JsonNode>("component", component)
        obj.set<JsonNode>("variants", factory.objectNode())
        return obj
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
        "registered v3 components endpoint with reordered entries produces zero structural diffs after sort",
    )
    fun v3Components_sameElementsDifferentOrder_zeroShapeDiffs() {
        val endpoint = "GET /rest/api/3/components"

        val baseline = factory.arrayNode().apply {
            add(v3Entry("alpha-fixture"))
            add(v3Entry("beta-fixture"))
        }
        val candidate = factory.arrayNode().apply {
            add(v3Entry("beta-fixture"))
            add(v3Entry("alpha-fixture"))
        }

        val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline)
        val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate)

        val diffs = JsonShape.diff(baselineSorted, candidateSorted)
        assertTrue(diffs.isEmpty(), "expected zero diffs, got: $diffs")
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
