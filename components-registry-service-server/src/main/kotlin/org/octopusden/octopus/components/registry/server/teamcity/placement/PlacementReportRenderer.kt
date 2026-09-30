package org.octopusden.octopus.components.registry.server.teamcity.placement

/**
 * Renders a [PlacementDiffResult] as a human-readable HTML report or a CSV export — the two
 * download formats the design brief asks for alongside the JSON the Portal table consumes
 * directly. Pure functions, no framework dependency; the controller sets the content type.
 */
object PlacementReportRenderer {
    private val csvSpecialChars = charArrayOf(',', '"', '\n', '\r')

    fun toHtml(result: PlacementDiffResult): String {
        val rowsHtml = result.rows.joinToString("\n") { row ->
            val entriesHtml = row.entries.joinToString("<br/>") { e ->
                "${esc(e.name)} (${esc(e.vcsPath)}): " +
                    "<code>${esc(e.currentCheckoutDirectory ?: "(root)")}</code> / <code>${esc(e.currentSourcePath ?: "—")}</code>" +
                    " &rarr; <code>${esc(e.derivedCheckoutDirectory ?: "(root)")}</code> / <code>${esc(e.derivedSourcePath ?: "—")}</code>"
            }
            """
            |<tr class="status-${esc(row.status.name.lowercase())}">
            |  <td>${esc(row.componentKey)}</td>
            |  <td>${esc(row.versionRange)}</td>
            |  <td>${esc(row.rowLabel)}</td>
            |  <td>${esc(row.status.name)}</td>
            |  <td>$entriesHtml</td>
            |  <td><code>${esc(row.currentBuildWorkingDirectory ?: "(root)")}</code> &rarr; <code>${esc(
                row.derivedBuildWorkingDirectory ?: "(root)",
            )}</code></td>
            |  <td>${row.sourceBuildTypeIds.joinToString(", ") { esc(it) }}</td>
            |  <td>${row.notes.joinToString("<br/>") { esc(it) }}</td>
            |</tr>
            """.trimMargin()
        }
        return """
        |<!doctype html>
        |<html><head><meta charset="utf-8"><title>TeamCity placement diff</title>
        |<style>
        |table { border-collapse: collapse; width: 100%; font-family: monospace, sans-serif; font-size: 13px; }
        |td, th { border: 1px solid #ccc; padding: 4px 8px; vertical-align: top; text-align: left; }
        |tr.status-resolved { background: #eaffea; }
        |tr.status-manual_edit { background: #fff6da; }
        |tr.status-conflict, tr.status-unexpressible, tr.status-tc_error { background: #ffecec; }
        |</style>
        |</head><body>
        |<h1>TeamCity placement diff</h1>
        |<p>Generated at ${esc(result.generatedAt.toString())} &mdash; ${result.rows.size} row(s).</p>
        |<table>
        |<thead><tr><th>Component</th><th>Version range</th><th>Row</th><th>Status</th><th>Entries (current &rarr; derived)</th>
        |<th>Build Working Directory</th><th>Source build type(s)</th><th>Notes</th></tr></thead>
        |<tbody>
        |$rowsHtml
        |</tbody>
        |</table>
        |</body></html>
            """.trimMargin()
    }

    fun toCsv(result: PlacementDiffResult): String {
        val header = listOf(
            "componentKey",
            "versionRange",
            "rowLabel",
            "status",
            "entryName",
            "vcsPath",
            "currentCheckoutDirectory",
            "currentSourcePath",
            "derivedCheckoutDirectory",
            "derivedSourcePath",
            "currentBuildWorkingDirectory",
            "derivedBuildWorkingDirectory",
            "sourceBuildTypeIds",
            "notes",
        )
        val lines = mutableListOf(header.joinToString(",") { csvCell(it) })
        for (row in result.rows) {
            for (entry in row.entries) {
                lines += listOf(
                    row.componentKey,
                    row.versionRange,
                    row.rowLabel,
                    row.status.name,
                    entry.name,
                    entry.vcsPath,
                    entry.currentCheckoutDirectory.orEmpty(),
                    entry.currentSourcePath.orEmpty(),
                    entry.derivedCheckoutDirectory.orEmpty(),
                    entry.derivedSourcePath.orEmpty(),
                    row.currentBuildWorkingDirectory.orEmpty(),
                    row.derivedBuildWorkingDirectory.orEmpty(),
                    row.sourceBuildTypeIds.joinToString(";"),
                    row.notes.joinToString(";"),
                ).joinToString(",") { csvCell(it) }
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun csvCell(value: String): String {
        val needsQuoting = value.any { it in csvSpecialChars }
        return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun esc(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
