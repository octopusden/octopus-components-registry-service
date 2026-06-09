package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Focused in-memory unit tests for the import display-name collision pre-pass (the UNIQUE-column
 * guard). displayName is stored VERBATIM and nullable — there is no key backfill (that would
 * break the legacy v1/v2/v3 `$.name` wire), so the pre-pass only flags duplicate non-null names
 * across DISTINCT components. No Spring context / DSL fixtures — the pure logic is exposed as an
 * `internal` companion function on [ImportServiceImpl].
 */
@Tag("unit")
class DisplayNameResolutionTest {
    @Test
    @DisplayName("computeDisplayNameCollisions: null/blank names are skipped (multiple NULLs never collide)")
    fun `null and blank are skipped`() {
        val modules =
            listOf(
                "COMP_A" to null,
                "COMP_B" to "",
                "COMP_C" to "   ",
                "COMP_D" to "Explicit D",
            )
        assertTrue(ImportServiceImpl.computeDisplayNameCollisions(modules).isEmpty())
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: distinct names do not collide")
    fun `distinct names do not collide`() {
        val modules =
            listOf(
                "COMP_X" to "Name X",
                "COMP_Y" to "Name Y",
            )
        assertTrue(ImportServiceImpl.computeDisplayNameCollisions(modules).isEmpty())
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: two distinct components with the same name are reported with both keys")
    fun `explicit duplicates reported`() {
        val modules =
            listOf(
                "COMP_X" to "Same Name",
                "COMP_Y" to "Same Name",
                "COMP_Z" to "Unique",
            )
        val collisions = ImportServiceImpl.computeDisplayNameCollisions(modules)
        assertEquals(setOf("Same Name"), collisions.keys)
        assertEquals(listOf("COMP_X", "COMP_Y"), collisions["Same Name"])
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: the SAME component key declared twice (dup DSL block) is NOT a collision")
    fun `same key declared twice is not a collision`() {
        // Mirrors the real DSL hygiene case (e.g. a component block copy-pasted in Archived.groovy):
        // one logical component, so it must not trip the unique pre-pass.
        val modules =
            listOf(
                "w4w_reports_server" to "w4w_reports_server (archived)",
                "w4w_reports_server" to "w4w_reports_server (archived)",
            )
        assertTrue(ImportServiceImpl.computeDisplayNameCollisions(modules).isEmpty())
    }
}
