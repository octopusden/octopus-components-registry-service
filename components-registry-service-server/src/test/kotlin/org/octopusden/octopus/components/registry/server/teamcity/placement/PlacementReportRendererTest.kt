package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PlacementReportRendererTest {
    @Test
    fun `HTML report escapes an apostrophe the same way Spring's HtmlUtils does (owner review finding 7, RED)`() {
        // Owner review: the hand-rolled esc() only replaces &, <, >, " -- never a single quote,
        // which is attacker-controlled (a component key, a note) and can still break out of a
        // single-quoted HTML attribute. Spring's HtmlUtils.htmlEscape covers it.
        val row = PlacementRowDiff(
            componentId = UUID.randomUUID(),
            componentKey = "comp-one",
            configurationRowId = UUID.randomUUID(),
            versionRange = "(,0),[0,)",
            rowLabel = "BASE",
            status = PlacementDiffRowStatus.CONFLICT,
            entries = emptyList(),
            currentBuildWorkingDirectory = null,
            derivedBuildWorkingDirectory = null,
            sourceBuildTypeIds = emptyList(),
            notes = listOf("can't be derived"),
        )
        val html = PlacementReportRenderer.toHtml(PlacementDiffResult(Instant.now(), listOf(row)))

        assertFalse(html.contains("can't be derived"))
        assertTrue(html.contains("can&#39;t be derived"))
    }

    @Test
    fun `sync report CSV lists one row per written field, with before and after`() {
        val result = PlacementSyncResult(
            triggeredBy = "alice",
            requested = 1,
            applied = 1,
            skipped = 0,
            failed = 0,
            components = emptyList(),
            fieldChanges = listOf(
                PlacementFieldChange("comp-one", "BASE", "main", "checkoutDirectory", null, "app"),
                PlacementFieldChange("comp-one", "BASE", "", "buildWorkingDirectory", null, "app/build"),
            ),
        )

        val csv = PlacementReportRenderer.toSyncReportCsv(result)

        val lines = csv.trim().split("\r\n")
        assertEquals(3, lines.size) // header + 2 rows
        assertEquals("componentKey,rowLabel,root,field,before,after", lines[0])
        assertTrue(lines[1].contains("comp-one,BASE,main,checkoutDirectory,,app"))
        assertTrue(lines[2].contains("comp-one,BASE,,buildWorkingDirectory,,app/build"))
    }
}
