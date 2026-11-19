package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO

/**
 * RES-027: the candidate keeps every part of a requested version in VCS settings, where the
 * baseline cut it to the major version and padded it to the build format (`1.0.4` → tag
 * `<module>-1.0.0-0`). The vcs-settings and detailed-component endpoints of both modes differ in
 * that one tag; the difference is a suppressed known delta. Anything else in the same record stays
 * active: another field of the root, another major version, a baseline tag that is not a padded major.
 * Same matching rules as [VcsCheckoutLayoutKnownDeltaTest].
 */
@Tag("unit")
class VcsSettingsVersionKnownDeltaTest {
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

    private fun settings(
        tag: String,
        branch: String = "master",
    ) = """{"versionControlSystemRoots":[{"name":"main","vcsPath":"ssh://git@example.test/proj/alpha.git",""" +
        """"type":"GIT","tag":"$tag","branch":"$branch"}],"externalRegistry":null}"""

    private fun version(
        type: String,
        version: String,
    ) = """{"type":"$type","version":"$version","jiraVersion":"$version"}"""

    private fun detailed(vcsSettings: String) =
        """{"id":"alpha-fixture","name":"Alpha","componentOwner":"user","buildSystem":"MAVEN","vcsSettings":$vcsSettings,""" +
            """"jiraComponentVersion":{"name":"alpha-fixture","version":"1","component":{"projectKey":"PRJX","displayName":null,""" +
            """"componentVersionFormat":{"majorVersionFormat":"${'$'}major",""" +
            """"releaseVersionFormat":"${'$'}major.${'$'}minor.${'$'}service",""" +
            """"buildVersionFormat":"${'$'}major.${'$'}minor.${'$'}service","lineVersionFormat":"${'$'}major",""" +
            """"hotfixVersionFormat":"${'$'}major.${'$'}minor.${'$'}service-${'$'}fix"},""" +
            """"componentInfo":{"versionPrefix":"","versionFormat":"${'$'}versionPrefix-${'$'}baseVersionFormat"},"technical":false}},""" +
            """"detailedComponentVersion":{"component":"Alpha","minorVersion":${version("MINOR", "1")},""" +
            """"lineVersion":${version("LINE", "1")},"buildVersion":${version("BUILD", "1.0.0")},""" +
            """"rcVersion":${version("RC", "1.0.0_RC")},"releaseVersion":${version("RELEASE", "1.0.0")}},""" +
            """"deprecated":false,"buildFilePath":null}"""

    private fun response(body: String) =
        RawResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = body.toByteArray(Charsets.UTF_8),
            json = mapper.readTree(body),
            durationMs = 0L,
        )

    /** Both layers, as the compat tests drive them: raw, then typed on 2xx. */
    private fun records(
        endpoint: String,
        baseline: String,
        candidate: String,
    ): List<DiffRecord> {
        DiffCollector.clear()
        if (endpoint == VCS_SETTINGS) {
            Comparators.compareRaw(endpoint, PARAMS, response(baseline), response(candidate))
            Comparators.compareDto(
                endpoint,
                PARAMS,
                mapper.readValue<VCSSettingsDTO>(baseline),
                mapper.readValue<VCSSettingsDTO>(candidate),
            )
        } else {
            val baselineBody = detailed(baseline)
            val candidateBody = detailed(candidate)
            Comparators.compareRaw(endpoint, PARAMS, response(baselineBody), response(candidateBody))
            Comparators.compareDto(
                endpoint,
                PARAMS,
                mapper.readValue<DetailedComponent>(baselineBody),
                mapper.readValue<DetailedComponent>(candidateBody),
            )
        }
        return DiffCollector.snapshot()
    }

    @BeforeEach
    fun clearCollector() {
        DiffCollector.clear()
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(
        textBlock = """known-deltas-git.json, alpha-1.0.0-0, alpha-1.0.4
            known-deltas-git.json, alpha-9.0.0,   alpha-9.11
            known-deltas-git.json, 2.0.0,         2.2
            known-deltas-db.json,  alpha-1.0.0-0, alpha-1.0.4
            known-deltas-db.json,  alpha-9.0.0,   alpha-9.11""",
    )
    @DisplayName("RES-027: a tag cut to the padded major on the baseline is a suppressed known delta")
    fun `RES-027 a cut version tag is suppressed`(
        file: String,
        baselineTag: String,
        candidateTag: String,
    ) {
        val deltas = deltas(file)
        listOf(VCS_SETTINGS, DETAILED_COMPONENT).forEach { endpoint ->
            val records = records(endpoint, settings(baselineTag), settings(candidateTag))

            assertThat(records).describedAs(endpoint).isNotEmpty
            assertThat(records.filterNot { suppressed(deltas, it) }).describedAs(endpoint).isEmpty()
        }
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}, branch {3}")
    @CsvSource(
        textBlock = """known-deltas-git.json, alpha-1.0.3,   alpha-1.0.4, master
            known-deltas-git.json, alpha-1.0.0-0, alpha-2.0.4, master
            known-deltas-git.json, alpha-1.0.0-0, beta-1.0.4,  master
            known-deltas-git.json, alpha-1.0.0-0, alpha-1.0.4, release
            known-deltas-db.json,  alpha-1.0.3,   alpha-1.0.4, master
            known-deltas-db.json,  alpha-1.0.0-0, alpha-1.0.4, release""",
    )
    @DisplayName("RES-027: another tag or root change stays active")
    fun `RES-027 another change stays active`(
        file: String,
        baselineTag: String,
        candidateTag: String,
        candidateBranch: String,
    ) {
        val deltas = deltas(file)
        listOf(VCS_SETTINGS, DETAILED_COMPONENT).forEach { endpoint ->
            val records = records(endpoint, settings(baselineTag), settings(candidateTag, candidateBranch))

            assertThat(records.filterNot { suppressed(deltas, it) }).describedAs(endpoint).isNotEmpty
        }
    }

    private companion object {
        const val VCS_SETTINGS = "GET /rest/api/2/components/{component}/versions/{version}/vcs-settings"
        const val DETAILED_COMPONENT = "GET /rest/api/2/components/{component}/versions/{version}"
        val PARAMS = mapOf("component" to "alpha-fixture", "version" to "1.0.4")
    }
}
