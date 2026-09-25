package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO

/**
 * ONB-001: a migrated candidate serves `checkoutDirectory` on secondary VCS roots (V8 back-fill), the
 * baseline does not. Drives the real comparators over that shape and checks every resulting record
 * against the committed `known-deltas-db.json`, with the matching rules of `compatMatchesDelta`
 * (build.gradle): endpoint, category, and `find()` on `jsonPathPattern` / `messagePattern`.
 */
@Tag("unit")
class VcsPlacementKnownDeltaTest {
    private val mapper = jacksonObjectMapper()

    private val deltas: List<Map<String, Any?>> =
        mapper.readValue<Map<String, Any?>>(javaClass.getResource("/known-deltas-db.json")!!).let {
            @Suppress("UNCHECKED_CAST")
            it["deltas"] as List<Map<String, Any?>>
        }

    private fun root(
        name: String,
        placement: String = "",
    ) = """{"name":"$name","vcsPath":"ssh://git@example.test/proj/$name.git","type":"GIT","branch":"master"$placement}"""

    private fun settings(vararg roots: String) = """{"versionControlSystemRoots":[${roots.joinToString(",")}],"externalRegistry":null}"""

    private fun ranges(vcsSettings: String) =
        """[{"componentName":"alpha-fixture","versionRange":"[1.0,)",""" +
            """"component":{"projectKey":"PRJX","displayName":null,"componentVersionFormat":{"majorVersionFormat":"${'$'}major",""" +
            """"releaseVersionFormat":"${'$'}major.${'$'}minor","buildVersionFormat":"${'$'}major.${'$'}minor-${'$'}build",""" +
            """"hotfixVersionFormat":"${'$'}major.${'$'}minor-${'$'}build","lineVersionFormat":"${'$'}major"},""" +
            """"componentInfo":{"versionPrefix":"alpha",""" +
            """"versionFormat":"${'$'}versionPrefix-${'$'}baseVersionFormat"},"technical":false},""" +
            """"distribution":{"explicit":false,"external":true},"vcsSettings":$vcsSettings}]"""

    private fun response(body: String) =
        RawResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = body.toByteArray(Charsets.UTF_8),
            json = mapper.readTree(body),
            durationMs = 0L,
        )

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

    private fun compare(
        endpoint: String,
        baseline: String,
        candidate: String,
    ): List<DiffRecord> {
        Comparators.compareRaw(endpoint, mapOf("p" to "x"), response(baseline), response(candidate))
        if (endpoint.endsWith("/vcs-settings")) {
            Comparators.compareDto(
                endpoint,
                mapOf("p" to "x"),
                mapper.readValue<VCSSettingsDTO>(baseline),
                mapper.readValue<VCSSettingsDTO>(candidate),
            )
        } else {
            Comparators.compareDto(
                endpoint,
                mapOf("p" to "x"),
                mapper.readValue<List<JiraComponentVersionRangeDTO>>(baseline),
                mapper.readValue<List<JiraComponentVersionRangeDTO>>(candidate),
            )
        }
        return DiffCollector.snapshot()
    }

    @BeforeEach
    fun clearCollector() {
        DiffCollector.clear()
    }

    @Test
    @DisplayName("ONB-001: checkoutDirectory on a secondary VCS root is a suppressed known delta on all four v2 endpoints")
    fun `secondary checkoutDirectory is suppressed`() {
        val baseline = settings(root("alpha"), root("beta"))
        val candidate = settings(root("alpha"), root("beta", ""","checkoutDirectory":"beta""""))
        val cases =
            listOf(
                "GET /rest/api/2/components/{component}/versions/{version}/vcs-settings" to (baseline to candidate),
                "GET /rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings" to (baseline to candidate),
                "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges" to (ranges(baseline) to ranges(candidate)),
                "GET /rest/api/2/common/jira-component-version-ranges" to (ranges(baseline) to ranges(candidate)),
            )
        cases.forEach { (endpoint, bodies) ->
            DiffCollector.clear()
            val records = compare(endpoint, bodies.first, bodies.second)

            assertThat(records).describedAs(endpoint).isNotEmpty
            assertThat(records.filterNot(::suppressed)).describedAs(endpoint).isEmpty()
        }
    }

    @Test
    @DisplayName("ONB-001: checkoutDirectory on the primary VCS root is NOT suppressed")
    fun `primary checkoutDirectory is not suppressed`() {
        val records =
            compare(
                "GET /rest/api/2/components/{component}/versions/{version}/vcs-settings",
                settings(root("alpha")),
                settings(root("alpha", ""","checkoutDirectory":"alpha"""")),
            )

        assertThat(records.filterNot(::suppressed)).isNotEmpty
    }
}
