package org.octopusden.octopus.components.registry.cli.output

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.octopusden.octopus.components.registry.cli.client.CrsApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RendererTest {
    private val parser = Json

    @Test
    fun `renderError from CrsApiException carries errorCode and message`() {
        val out = Renderer.renderError(CrsApiException(404, "NOT_FOUND", "missing component"))
        val obj = parser.parseToJsonElement(out) as JsonObject
        assertEquals("NOT_FOUND", obj["errorCode"]!!.jsonPrimitive.content)
        assertEquals("missing component", obj["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `renderError null errorCode serializes as JSON null`() {
        val out = Renderer.renderError(RuntimeException("boom"))
        val obj = parser.parseToJsonElement(out) as JsonObject
        assertTrue(obj.containsKey("errorCode"))
        assertEquals(JsonNull, obj["errorCode"])
        assertEquals("boom", obj["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `renderError falls back to class name when message is null`() {
        val out = Renderer.renderError(NullPointerException())
        val obj = parser.parseToJsonElement(out) as JsonObject
        assertEquals("NullPointerException", obj["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `renderTable aligns columns and handles null cells`() {
        val table = Renderer.renderTable(
            headers = listOf("ID", "NAME"),
            rows = listOf(
                listOf("a", "Alpha"),
                listOf("bbbb", null),
            ),
        )
        val lines = table.lines()
        assertEquals("ID    NAME", lines[0])
        assertEquals("a     Alpha", lines[1])
        assertEquals("bbbb", lines[2])
    }

    @Test
    fun `renderTable with no rows emits only header`() {
        val table = Renderer.renderTable(listOf("ID", "NAME"), emptyList())
        assertEquals("ID  NAME", table)
    }
}
