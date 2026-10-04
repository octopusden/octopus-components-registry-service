package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ComponentV2

/**
 * SYS-099: a migrated candidate serves `testComponent: true` on components labelled `test-component`
 * (V11 back-fill); the baseline has no flag. The added field is a suppressed known delta on both
 * layers, a dropped flag is not. Same matching rules as [VcsPlacementKnownDeltaTest].
 */
@Tag("unit")
class TestComponentKnownDeltaTest {
    private val mapper = jacksonObjectMapper()

    private val deltas: List<Map<String, Any?>> =
        mapper.readValue<Map<String, Any?>>(javaClass.getResource("/known-deltas-db.json")!!).let {
            @Suppress("UNCHECKED_CAST")
            it["deltas"] as List<Map<String, Any?>>
        }

    private fun suppressed(record: DiffRecord): Boolean =
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

    @Test
    @DisplayName("SYS-099: an added testComponent flag is suppressed on templated and literal component paths")
    fun `added flag is suppressed`() {
        listOf("GET /rest/api/2/components/{component}", "GET /rest/api/1/components/alpha-fixture").forEach { endpoint ->
            DiffCollector.clear()
            val records = records(endpoint, component(), component(""","testComponent":true"""))

            assertThat(records).describedAs(endpoint).isNotEmpty
            assertThat(records.filterNot(::suppressed)).describedAs(endpoint).isEmpty()
        }
    }

    @Test
    @DisplayName("SYS-099: a dropped testComponent flag stays active")
    fun `dropped flag is not suppressed`() {
        val records = records("GET /rest/api/2/components/{component}", component(""","testComponent":true"""), component())

        assertThat(records.filterNot(::suppressed)).describedAs(records.toString()).isNotEmpty
    }
}
