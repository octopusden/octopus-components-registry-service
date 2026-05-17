package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pure-unit coverage for [TraceReplayCompatTest.Companion.parsePathAndQuery].
 *
 * This helper is the methodology fix that follows from the discovery that
 * passing the raw trace `path` (which embeds the query string) as the
 * `endpoint` argument to [Comparators.compareRaw] caused
 * [RawArraySorters.stableSorted] lookups to miss — the sorter is keyed on the
 * exact endpoint string. Stripping the query before building the endpoint
 * restores the lookup; surfacing the parsed query in `queryParams` keeps the
 * `_weight` bucket-counter plus per-parameter context available in the
 * resulting [DiffRecord]s for downstream analysis.
 *
 * See the class KDoc on [TraceReplayCompatTest] for the methodology rationale.
 */
@Tag("unit")
class TraceReplayParsingTest {

    @Test
    @DisplayName("path with no '?' returns the whole string and empty query map")
    fun noQuery() {
        val (path, query) = TraceReplayCompatTest.parsePathAndQuery("/rest/api/2/components")
        assertThat(path).isEqualTo("/rest/api/2/components")
        assertThat(query).isEmpty()
    }

    @Test
    @DisplayName("single key=value pair is parsed and URL-decoded")
    fun singleParamDecoded() {
        // %3A → ":" — exactly the kind of encoding the prod trace contains
        // for vcs-path values like ssh://host:port/repo.git
        val (path, query) =
            TraceReplayCompatTest.parsePathAndQuery("/rest/api/2/components?vcs-path=ssh%3A%2F%2Fhost%2Frepo")
        assertThat(path).isEqualTo("/rest/api/2/components")
        assertThat(query).containsExactlyEntriesOf(mapOf("vcs-path" to "ssh://host/repo"))
    }

    @Test
    @DisplayName("multiple '&'-separated pairs are parsed independently")
    fun multipleParams() {
        val (path, query) =
            TraceReplayCompatTest.parsePathAndQuery("/foo?a=1&b=2&c=hello%20world")
        assertThat(path).isEqualTo("/foo")
        assertThat(query)
            .containsEntry("a", "1")
            .containsEntry("b", "2")
            .containsEntry("c", "hello world")
            .hasSize(3)
    }

    @Test
    @DisplayName("trailing '?' with empty query yields empty map (not a null)")
    fun emptyQuery() {
        val (path, query) = TraceReplayCompatTest.parsePathAndQuery("/foo?")
        assertThat(path).isEqualTo("/foo")
        assertThat(query).isEmpty()
    }

    @Test
    @DisplayName("only the FIRST '?' is the splitter — encoded '?' in path stays put")
    fun firstQuestionWins() {
        // If a downstream caller ever sends ?'s embedded in a value (it
        // happens when a search parameter itself contains a literal ?),
        // we still cut at the first occurrence — anything after lives in
        // the query, and the path keeps everything before. The second '?'
        // ends up as a literal in the LAST value, since '?' is not a
        // separator inside the query component.
            val (path, query) =
                TraceReplayCompatTest.parsePathAndQuery("/search?q=what%3F&filter=x?y")
        assertThat(path).isEqualTo("/search")
        // value "x?y" is preserved as-is (raw '?' is legal inside a query value)
        assertThat(query).containsEntry("filter", "x?y")
        assertThat(query).containsEntry("q", "what?")
    }

    @Test
    @DisplayName("key without '=' maps to empty value (e.g. /foo?flag)")
    fun flagOnlyParam() {
        val (path, query) = TraceReplayCompatTest.parsePathAndQuery("/foo?flag")
        assertThat(path).isEqualTo("/foo")
        assertThat(query).containsExactlyEntriesOf(mapOf("flag" to ""))
    }

    @Test
    @DisplayName("RED-guard: endpoint key built from parsed path must NOT include the '?' suffix")
    fun endpointKeyExcludesQuery() {
        // This is the methodology bug captured as an assertion: the path-only
        // half of the parse output is what gets concatenated with the method
        // to form the endpoint key passed to Comparators.compareRaw, and that
        // key MUST be a stable, query-free string so RawArraySorters lookups
        // hit the registered template.
        val raw = "/rest/api/2/components?vcs-path=ssh%3A%2F%2Fhost%2Frepo"
        val (pathOnly, _) = TraceReplayCompatTest.parsePathAndQuery(raw)
        val endpoint = "GET $pathOnly"
        assertThat(endpoint).doesNotContain("?")
        assertThat(endpoint).isEqualTo("GET /rest/api/2/components")
    }
}
