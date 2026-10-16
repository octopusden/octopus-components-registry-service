package org.octopusden.octopus.components.registry.server.teamcity.validation

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRootEntry

/**
 * TeamCity returns only the columns a `fields=` spec names, and the client's DTOs have non-null
 * columns. A VCS root without `name` failed to deserialize on QA, so every project with a VCS root
 * came back as a TeamCity error in the placement Diff.
 */
class EnrichedTcProjectFetcherFieldsTest {
    @Test
    fun `a vcs-root-entry shaped exactly to the requested fields deserializes`() {
        val vcsRootSelector = "vcs-root-entry(id,checkout-rules,vcs-root(id,name,href,properties(property(name,value))))"
        assertTrue(CachingEnrichedTcProjectFetcher.FIELDS.contains(vcsRootSelector), "FIELDS must request $vcsRootSelector")
        // Only the columns vcsRootSelector names, as TeamCity returns them.
        val json =
            """
            {
              "id": "Prj_App",
              "checkout-rules": "+:. => app",
              "vcs-root": {
                "id": "Prj_App",
                "name": "app",
                "href": "/app/rest/vcs-roots/id:Prj_App",
                "properties": {"property": [{"name": "url", "value": "ssh://h/prj/app.git"}]}
              }
            }
            """.trimIndent()
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val entry = mapper.readValue(json, TeamcityVcsRootEntry::class.java)

        assertEquals("+:. => app", entry.checkoutRules)
        assertEquals("Prj_App", entry.vcsRoot.id)
    }
}
