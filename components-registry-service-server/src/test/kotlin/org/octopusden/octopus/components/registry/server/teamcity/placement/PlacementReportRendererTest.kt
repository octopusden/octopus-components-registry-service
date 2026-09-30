package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacementReportRendererTest {
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
