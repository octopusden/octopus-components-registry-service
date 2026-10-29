package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Unit tests for [loadVersionsFile]. The integration with [VersionSampler.versionsFor]
 * is harder to exercise (lazy-initialised singleton state) — these tests cover the
 * pure-function loader directly, which is the whole non-trivial part.
 */
@Tag("unit")
class VersionSamplerLoaderTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `null path returns null — caller falls back to RMS`() {
        assertThat(loadVersionsFile(null, mapper)).isNull()
    }

    @Test
    fun `non-existent path returns empty map — operator opted into file mode, missing file is operator error`(
        @TempDir tmp: Path,
    ) {
        val out = loadVersionsFile(tmp.resolve("does-not-exist.json").toString(), mapper)
        assertThat(out).isEqualTo(emptyMap<String, List<String>>())
    }

    @Test
    fun `directory at path returns empty map`(
        @TempDir tmp: Path,
    ) {
        // canRead() may return true on a directory; isFile() must reject it.
        val out = loadVersionsFile(tmp.toString(), mapper)
        assertThat(out).isEqualTo(emptyMap<String, List<String>>())
    }

    @Test
    fun `unparseable JSON returns empty map and logs warning`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("bad.json")
        f.writeText("{not json")
        val warnings = mutableListOf<String>()
        val out = loadVersionsFile(f.toString(), mapper, warn = { warnings += it })
        assertThat(out).isEqualTo(emptyMap<String, List<String>>())
        assertThat(warnings).anyMatch { it.contains("failed to parse") }
    }

    @Test
    fun `non-object JSON (array root) returns empty map`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("array.json")
        f.writeText("""["a", "b"]""")
        val out = loadVersionsFile(f.toString(), mapper)
        assertThat(out).isEqualTo(emptyMap<String, List<String>>())
    }

    @Test
    fun `happy path — JSON object of string arrays loaded into map`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("ok.json")
        f.writeText("""{"comp-a": ["1.0.0", "0.9.0"], "comp-b": ["2.1.5"]}""")
        val out = loadVersionsFile(f.toString(), mapper)
        assertThat(out).containsExactlyInAnyOrderEntriesOf(
            mapOf("comp-a" to listOf("1.0.0", "0.9.0"), "comp-b" to listOf("2.1.5")),
        )
    }

    @Test
    fun `non-array entry values are skipped — partial files don't crash`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("mixed.json")
        f.writeText("""{"comp-a": ["1.0"], "comp-b": "not-an-array", "comp-c": null, "comp-d": []}""")
        val out = loadVersionsFile(f.toString(), mapper)
        // comp-a kept; comp-b/c skipped (non-array); comp-d kept with empty list.
        assertThat(out).containsExactlyInAnyOrderEntriesOf(
            mapOf("comp-a" to listOf("1.0"), "comp-d" to emptyList()),
        )
    }

    @Test
    fun `array elements that are not non-blank strings are filtered out`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("dirty.json")
        f.writeText("""{"comp": ["1.0", "", "   ", null, 42, "2.0", true]}""")
        val out = loadVersionsFile(f.toString(), mapper)
        assertThat(out).isEqualTo(mapOf("comp" to listOf("1.0", "2.0")))
    }

    @Test
    fun `info logger receives the count of loaded components on success`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("ok.json")
        f.writeText("""{"a": ["1"], "b": ["2"], "c": ["3"]}""")
        val infos = mutableListOf<String>()
        loadVersionsFile(f.toString(), mapper, info = { infos += it })
        assertThat(infos).anyMatch { it.contains("loaded 3 components") }
    }
}
