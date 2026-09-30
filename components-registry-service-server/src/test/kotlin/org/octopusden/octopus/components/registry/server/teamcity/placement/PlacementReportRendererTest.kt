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
    fun `diff report CSV has the expected header row (spec-conformance finding 4 coverage)`() {
        val csv = PlacementReportRenderer.toCsv(PlacementDiffResult(Instant.now(), emptyList()))

        val header = csv.trim().split("\r\n").single()
        assertEquals(
            "componentKey,versionRange,rowLabel,status,entryName,vcsPath,currentCheckoutDirectory," +
                "currentSourcePath,derivedCheckoutDirectory,derivedSourcePath,currentBuildWorkingDirectory," +
                "derivedBuildWorkingDirectory,sourceBuildTypeIds,notes",
            header,
        )
    }

    @Test
    fun `diff report CSV escapes a comma in a note by quoting the cell (spec-conformance finding 4 coverage)`() {
        val row = PlacementRowDiff(
            componentId = UUID.randomUUID(),
            componentKey = "comp-one",
            configurationRowId = UUID.randomUUID(),
            versionRange = "(,0),[0,)",
            rowLabel = "BASE",
            status = PlacementDiffRowStatus.OUTSIDE_SCOPE,
            entries = listOf(
                PlacementEntryDiff(
                    name = "main",
                    vcsPath = "ssh://h/prj/app.git",
                    branch = null,
                    tag = null,
                    hotfixBranch = null,
                    repositoryType = "GIT",
                    currentCheckoutDirectory = null,
                    currentSourcePath = null,
                    derivedCheckoutDirectory = null,
                    derivedSourcePath = null,
                ),
            ),
            currentBuildWorkingDirectory = null,
            derivedBuildWorkingDirectory = null,
            sourceBuildTypeIds = emptyList(),
            notes = listOf("archived, report-only"),
        )

        val csv = PlacementReportRenderer.toCsv(PlacementDiffResult(Instant.now(), listOf(row)))

        val dataLine = csv.trim().split("\r\n")[1]
        assertTrue(dataLine.contains("\"archived, report-only\""))
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
