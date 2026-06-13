package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesEmployeeMatchArray() {
        val literal = """
            [
              { "username": "alice", "active": true },
              { "username": "bob", "active": false }
            ]
        """.trimIndent()

        val matches = json.decodeFromString<List<EmployeeMatchResponse>>(literal)

        assertEquals(2, matches.size)
        assertEquals("alice", matches[0].username)
        assertEquals(true, matches[0].active)
        assertEquals(false, matches[1].active)
    }

    @Test
    fun decodesEditorsResponse() {
        val literal = """
            {
              "componentOwner": "owner1",
              "releaseManagers": ["rm1", "rm2"],
              "securityChampions": ["sc1"]
            }
        """.trimIndent()

        val editors = json.decodeFromString<ComponentEditorsResponse>(literal)

        assertEquals("owner1", editors.componentOwner)
        assertEquals(listOf("rm1", "rm2"), editors.releaseManagers)
        assertEquals(listOf("sc1"), editors.securityChampions)
    }

    @Test
    fun decodesEmployeeHealth() {
        val literal = """{ "status": "UP" }"""

        val health = json.decodeFromString<EmployeeIntegrationHealthResponse>(literal)

        assertEquals("UP", health.status)
    }
}
