package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAuthMe() {
        val literal = """
            {
              "username": "jdoe",
              "groups": ["dev", "release"],
              "roles": [
                { "name": "EDITOR", "permissions": ["EDIT_METADATA", "EDIT_ANY_COMPONENT"] },
                { "name": "VIEWER", "permissions": ["READ"] }
              ],
              "extraServerField": true
            }
        """.trimIndent()

        val user = json.decodeFromString<User>(literal)

        assertEquals("jdoe", user.username)
        assertEquals(listOf("dev", "release"), user.groups)
        assertEquals(2, user.roles.size)
        assertEquals("EDITOR", user.roles.first().name)
        assertEquals(listOf("EDIT_METADATA", "EDIT_ANY_COMPONENT"), user.roles.first().permissions)
    }
}
