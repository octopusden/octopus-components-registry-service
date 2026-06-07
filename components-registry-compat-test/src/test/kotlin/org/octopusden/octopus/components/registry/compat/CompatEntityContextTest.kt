package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CompatEntityContextTest {
    private val mapper = jacksonObjectMapper()

    @Test
    @DisplayName("resolveEntityKey maps jira-ranges array index to componentName @ versionRange")
    fun resolveEntityKey_mapsJiraRangesIndex() {
        val baseline =
            mapper.readTree(
                """
                [
                  {"componentName":"alpha","versionRange":"[1.0,2.0)","component":{"projectKey":"PRJX"}},
                  {"componentName":"beta","versionRange":"[2.0,)","component":{"projectKey":"PRJX"}}
                ]
                """.trimIndent(),
            )

        val key =
            CompatEntityContext.resolveEntityKey(
                endpoint = "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges",
                jsonPath = "\$[1].component.displayName",
                pathParams = mapOf("projectKey" to "PRJX"),
                baselineJson = baseline,
                candidateJson = null,
            )

        assertEquals("PRJX / beta @ [2.0,)", key)
    }

    @Test
    @DisplayName("resolveEntityKey uses path params for per-version distribution endpoint")
    fun resolveEntityKey_usesPathParamsForDistribution() {
        val key =
            CompatEntityContext.resolveEntityKey(
                endpoint = "GET /rest/api/2/components/{component}/versions/{version}/distribution",
                jsonPath = "$",
                pathParams = mapOf("component" to "sample-lib", "version" to "12.1.155"),
                baselineJson = null,
                candidateJson = null,
            )

        assertEquals("sample-lib @ 12.1.155", key)
    }

    @Test
    @DisplayName("normalizeFieldPath collapses array indices")
    fun normalizeFieldPath_collapsesIndices() {
        assertEquals(
            "$" + "[N].vcsSettings.externalRegistry",
            CompatEntityContext.normalizeFieldPath("\$[23].vcsSettings.externalRegistry"),
        )
    }
}
