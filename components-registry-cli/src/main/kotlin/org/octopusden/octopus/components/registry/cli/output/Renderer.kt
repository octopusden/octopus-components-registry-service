package org.octopusden.octopus.components.registry.cli.output

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.octopusden.octopus.components.registry.cli.client.CrsApiException
import org.octopusden.octopus.components.registry.cli.client.Json
import org.octopusden.octopus.components.registry.cli.client.PrettyJson

/**
 * Renders command results to strings. The caller is responsible for the actual write to STDOUT /
 * STDERR; keeping the rendering pure makes it trivially testable.
 */
object Renderer {
    /** Pretty JSON for STDOUT. */
    fun <T> renderJson(
        value: T,
        serializer: KSerializer<T>,
    ): String = PrettyJson.encodeToString(serializer, value)

    /**
     * A simple fixed-width table for list-shaped data. [headers] names the columns; [rows] supplies
     * one cell per column per row. Null cells render as an empty string. Columns are padded to the
     * widest cell so output lines up. An empty [rows] renders just the header line.
     */
    fun renderTable(
        headers: List<String>,
        rows: List<List<String?>>,
    ): String {
        val widths = IntArray(headers.size) { headers[it].length }
        for (row in rows) {
            for (i in headers.indices) {
                val cell = row.getOrNull(i).orEmpty()
                if (cell.length > widths[i]) {
                    widths[i] = cell.length
                }
            }
        }
        val sb = StringBuilder()
        appendRow(sb, headers, widths)
        for (row in rows) {
            val cells = headers.indices.map { row.getOrNull(it).orEmpty() }
            appendRow(sb, cells, widths)
        }
        return sb.toString().trimEnd('\n')
    }

    private fun appendRow(
        sb: StringBuilder,
        cells: List<String>,
        widths: IntArray,
    ) {
        val line = cells.indices.joinToString("  ") { i ->
            cells[i].padEnd(widths[i])
        }
        sb.append(line.trimEnd()).append('\n')
    }

    /**
     * Structured error JSON for STDERR. Shape is fixed: {"errorCode": <code or null>, "message": <msg>}.
     * For [CrsApiException] the parsed errorCode/errorMessage are surfaced; for anything else the
     * errorCode is null and the message is the throwable's message (or its class name as a fallback).
     */
    fun renderError(throwable: Throwable): String {
        val (errorCode, message) = when (throwable) {
            is CrsApiException -> throwable.errorCode to throwable.errorMessage
            else -> null to (throwable.message ?: throwable::class.simpleName ?: "Unknown error")
        }
        val obj: JsonObject = buildJsonObject {
            put("errorCode", if (errorCode == null) JsonPrimitive(null as String?) else JsonPrimitive(errorCode))
            put("message", JsonPrimitive(message))
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }
}
