package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Generic regression-guard unit coverage for [JsonShape] itself.
 *
 * Complementary to (not duplicating) `RawArraySortersTest` introduced by PR #222 —
 * that suite covers the pre-sort layer that runs BEFORE `JsonShape.diff` for known
 * Set-shape endpoints. This suite pins the contract of `JsonShape.diff` directly:
 * key-order independence on objects, position-sensitive walk on arrays, the four
 * structural diff kinds (TYPE_MISMATCH, KEY_MISSING_BASELINE, KEY_MISSING_CANDIDATE,
 * ARRAY_SIZE_MISMATCH), and the path-encoding rule for keys with special chars.
 *
 * Each test constructs two minimal JsonNode fixtures and asserts the exact list of
 * `JsonShape.ShapeDiff`s the function should produce. A change to `JsonShape` that
 * silently misclassifies (FP or FN) breaks the corresponding test by going RED.
 */
@Tag("unit")
class JsonShapeTest {
    private val mapper = jacksonObjectMapper()

    private fun n(json: String): JsonNode = mapper.readTree(json)

    // --- Object key-order independence ---

    @Test
    fun `same object with different key arrival order — zero diffs`() {
        val baseline = n("""{"a": 1, "b": 2, "c": 3}""")
        val candidate = n("""{"c": 3, "a": 1, "b": 2}""")
        assertThat(JsonShape.diff(baseline, candidate)).isEmpty()
    }

    @Test
    fun `same nested object with permuted keys at every level — zero diffs`() {
        val baseline = n("""{"outer": {"x": 1, "y": 2}, "z": 3}""")
        val candidate = n("""{"z": 3, "outer": {"y": 2, "x": 1}}""")
        assertThat(JsonShape.diff(baseline, candidate)).isEmpty()
    }

    // --- Array position-sensitive walk (intentional — Set-shape endpoints route
    // through RawArraySorters pre-sort, not through this layer) ---

    @Test
    fun `array — same shape but different leaf VALUES at same index produce zero shape diffs`() {
        // Leaf-value differences are deliberately not structural at this layer; they
        // are handed off to the typed (DTO recursive) layer. Confirm JsonShape stays
        // out of value comparison.
        val baseline = n("""[{"k": "a"}, {"k": "b"}]""")
        val candidate = n("""[{"k": "b"}, {"k": "a"}]""")
        assertThat(JsonShape.diff(baseline, candidate)).isEmpty()
    }

    @Test
    fun `array — different leaf TYPES at same index produce TYPE_MISMATCH at that path`() {
        val baseline = n("""[{"k": "a"}, {"k": "b"}]""")
        val candidate = n("""[{"k": 1}, {"k": "b"}]""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().kind).isEqualTo(JsonShape.ShapeDiff.Kind.TYPE_MISMATCH)
        assertThat(diffs.single().path).isEqualTo("$[0].k")
        assertThat(diffs.single().baseline).isEqualTo("STRING")
        assertThat(diffs.single().candidate).isEqualTo("NUMBER")
    }

    // --- TYPE_MISMATCH on leaves ---

    @Test
    fun `TYPE_MISMATCH — different leaf types at same key`() {
        val baseline = n("""{"x": "string"}""")
        val candidate = n("""{"x": 42}""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().kind).isEqualTo(JsonShape.ShapeDiff.Kind.TYPE_MISMATCH)
        assertThat(diffs.single().path).isEqualTo("$.x")
        assertThat(diffs.single().baseline).isEqualTo("STRING")
        assertThat(diffs.single().candidate).isEqualTo("NUMBER")
    }

    // --- KEY_MISSING_* ---

    @Test
    fun `KEY_MISSING_CANDIDATE — baseline has key, candidate does not`() {
        val baseline = n("""{"x": 1, "y": 2}""")
        val candidate = n("""{"x": 1}""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().kind).isEqualTo(JsonShape.ShapeDiff.Kind.KEY_MISSING_CANDIDATE)
        assertThat(diffs.single().path).isEqualTo("$.y")
    }

    @Test
    fun `KEY_MISSING_BASELINE — candidate has key, baseline does not`() {
        val baseline = n("""{"x": 1}""")
        val candidate = n("""{"x": 1, "y": 2}""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().kind).isEqualTo(JsonShape.ShapeDiff.Kind.KEY_MISSING_BASELINE)
        assertThat(diffs.single().path).isEqualTo("$.y")
    }

    // --- ARRAY_SIZE_MISMATCH ---

    @Test
    fun `ARRAY_SIZE_MISMATCH — different sizes on positional list`() {
        val baseline = n("""{"items": [1, 2, 3]}""")
        val candidate = n("""{"items": [1, 2]}""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().kind).isEqualTo(JsonShape.ShapeDiff.Kind.ARRAY_SIZE_MISMATCH)
        assertThat(diffs.single().path).isEqualTo("$.items")
        assertThat(diffs.single().baseline).isEqualTo("3")
        assertThat(diffs.single().candidate).isEqualTo("2")
    }

    // --- Path encoding for keys with special characters
    // (regression guard for maven-artifacts response keys like "(,03.51.29.15)") ---

    @Test
    fun `key with brackets and commas is bracket-quoted in path`() {
        val baseline = n("""{"(,03.51.29.15)": {"k": "v1"}}""")
        val candidate = n("""{"(,03.51.29.15)": {"k": 1}}""")
        val diffs = JsonShape.diff(baseline, candidate)
        assertThat(diffs).hasSize(1)
        assertThat(diffs.single().path).isEqualTo("\$[\"(,03.51.29.15)\"].k")
    }
}
