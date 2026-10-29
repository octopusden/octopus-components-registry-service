package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesErrorWithCodeAndMessage() {
        val literal =
            """
            { "errorCode": "COMPONENT_NOT_FOUND", "errorMessage": "No component with id x" }
            """.trimIndent()

        val error = json.decodeFromString<ErrorResponse>(literal)

        assertEquals("COMPONENT_NOT_FOUND", error.errorCode)
        assertEquals("No component with id x", error.errorMessage)
    }

    @Test
    fun decodesErrorWithoutOptionalCode() {
        val literal = """{ "errorMessage": "boom" }"""

        val error = json.decodeFromString<ErrorResponse>(literal)

        assertNull(error.errorCode)
        assertEquals("boom", error.errorMessage)
    }
}
