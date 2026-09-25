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

    private fun settings(
        vararg roots: String,
        bwd: String? = null,
    ) = """{"versionControlSystemRoots":[${roots.joinToString(",")}],"externalRegistry":null""" +
        (bwd?.let { ""","buildWorkingDirectory":"$it"""" } ?: "") + "}"

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

    private val endpoints =
        listOf(
            "GET /rest/api/2/components/{component}/versions/{version}/vcs-settings",
            "GET /rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings",
            "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges",
            "GET /rest/api/2/common/jira-component-version-ranges",
        )

    private fun records(
        endpoint: String,
        baseline: String,
        candidate: String,
    ): List<DiffRecord> {
        DiffCollector.clear()
        return if (endpoint.endsWith("/vcs-settings")) {
            compare(endpoint, baseline, candidate)
        } else {
            compare(endpoint, ranges(baseline), ranges(candidate))
        }
    }

    @Test
    @DisplayName("ONB-001 rev. 3: placement on any VCS root and a Build Working Directory are suppressed known deltas on all four v2 endpoints")
    fun `placement and build working directory are suppressed`() {
        val baseline = settings(root("alpha"), root("beta"))
        val candidate =
            settings(
                root("alpha", ""","checkoutDirectory":"alpha","sourcePath":"mapper""""),
                root("beta", ""","sourcePath":"data""""),
                bwd = "alpha/mapper",
            )
        endpoints.forEach { endpoint ->
            val records = records(endpoint, baseline, candidate)

            assertThat(records).describedAs(endpoint).isNotEmpty
            assertThat(records.filterNot(::suppressed)).describedAs(endpoint).isEmpty()
        }
    }

    @Test
    @DisplayName("ONB-001 rev. 3: a changed Checkout Directory or Build Working Directory stays active")
    fun `changed placement is not suppressed`() {
        val baseline = settings(root("alpha", ""","checkoutDirectory":"alpha""""), root("beta"), bwd = "alpha")
        listOf(
            settings(root("alpha", ""","checkoutDirectory":"other""""), root("beta"), bwd = "alpha"),
            settings(root("alpha", ""","checkoutDirectory":"alpha""""), root("beta"), bwd = "beta"),
        ).forEach { candidate ->
            endpoints.forEach { endpoint ->
                assertThat(records(endpoint, baseline, candidate).filterNot(::suppressed)).describedAs(endpoint).isNotEmpty
            }
        }
    }
}
