package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.core.dto.ComponentV2

/**
 * SYS-099: every v1/v2/v3 component payload gains `testComponent` (false, or true for the
 * components V11 flagged); the baseline has no key. In both modes the added key is a suppressed
 * known delta on both layers; a dropped key is not. Same matching rules as [VcsPlacementKnownDeltaTest].
 */
@Tag("unit")
class TestComponentKnownDeltaTest {
    private val mapper = jacksonObjectMapper()

    private fun deltas(file: String): List<Map<String, Any?>> =
        mapper.readValue<Map<String, Any?>>(javaClass.getResource("/$file")!!).let {
            @Suppress("UNCHECKED_CAST")
            it["deltas"] as List<Map<String, Any?>>
        }

    private fun suppressed(
        deltas: List<Map<String, Any?>>,
        record: DiffRecord,
    ): Boolean =
        deltas.any { d ->
            (d["endpoint"] == null || d["endpoint"] == record.endpoint) &&
                (d["endpointPattern"] == null || Regex(d["endpointPattern"] as String).containsMatchIn(record.endpoint)) &&
                (d["endpoint"] != null || d["endpointPattern"] != null) &&
                d["category"] == record.category.name &&
                d["baselineValue"] == null &&
                d["candidateValue"] == null &&
                d["pathParams"] == null &&
                matchesIfSet(d["jsonPathPattern"], record.jsonPath) &&
                matchesIfSet(d["messagePattern"], record.message)
        }

    private fun matchesIfSet(
        pattern: Any?,
        value: String?,
    ) = pattern == null || (value != null && Regex(pattern as String).containsMatchIn(value))

    private fun component(flag: String = "") = """{"id":"alpha-fixture","name":null,"componentOwner":"user"$flag}"""

    private fun response(body: String) =
        RawResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = body.toByteArray(Charsets.UTF_8),
            json = mapper.readTree(body),
            durationMs = 0L,
        )

    private fun records(
        endpoint: String,
        baseline: String,
        candidate: String,
    ): List<DiffRecord> {
        DiffCollector.clear()
        Comparators.compareRaw(endpoint, mapOf("p" to "x"), response(baseline), response(candidate))
        Comparators.compareDto(
            endpoint,
            mapOf("p" to "x"),
            mapper.readValue<ComponentV2>(baseline),
            mapper.readValue<ComponentV2>(candidate),
        )
        return DiffCollector.snapshot()
    }

    @BeforeEach
    fun clearCollector() {
        DiffCollector.clear()
    }

    @ParameterizedTest
    @ValueSource(strings = ["known-deltas-db.json", "known-deltas-git.json"])
    @DisplayName("SYS-099: an added testComponent key (false or true) is suppressed on component and find-by-artifact paths")
    fun `added key is suppressed`(file: String) {
        val deltas = deltas(file)
        listOf(
            "GET /rest/api/2/components/{component}",
            "GET /rest/api/1/components/alpha-fixture",
            "GET /rest/api/3/components",
            "POST /rest/api/3/components/find-by-artifacts",
        ).forEach { endpoint ->
            listOf("false", "true").forEach { value ->
                val records = records(endpoint, component(), component(""","testComponent":$value"""))

                assertThat(records).describedAs("$endpoint $value").isNotEmpty
                assertThat(records.filterNot { suppressed(deltas, it) }).describedAs("$endpoint $value").isEmpty()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["known-deltas-db.json", "known-deltas-git.json"])
    @DisplayName("SYS-099: a dropped testComponent key stays active")
    fun `dropped key is not suppressed`(file: String) {
        val deltas = deltas(file)
        val records = records("GET /rest/api/2/components/{component}", component(""","testComponent":true"""), component())

        assertThat(records.filterNot { suppressed(deltas, it) }).describedAs(records.toString()).isNotEmpty
    }
}
