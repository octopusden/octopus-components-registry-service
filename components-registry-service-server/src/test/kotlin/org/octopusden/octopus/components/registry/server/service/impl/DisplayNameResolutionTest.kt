package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Focused in-memory unit tests for the import display-name backfill + collision pre-pass
 * (the data-sensitive NOT NULL + UNIQUE logic). No Spring context / DSL fixtures — the pure
 * logic is exposed as `internal` companion functions on [ImportServiceImpl].
 */
@Tag("unit")
class DisplayNameResolutionTest {
    private val default = "Common Default Name"

    @Test
    @DisplayName("resolveDisplayName: blank or null falls back to the component key")
    fun `blank or null falls back to key`() {
        assertEquals("COMP", ImportServiceImpl.resolveDisplayName("COMP", null, default))
        assertEquals("COMP", ImportServiceImpl.resolveDisplayName("COMP", "", default))
        assertEquals("COMP", ImportServiceImpl.resolveDisplayName("COMP", "   ", default))
    }

    @Test
    @DisplayName("resolveDisplayName: a value equal to the inherited default falls back to the key")
    fun `inherited default falls back to key`() {
        assertEquals("COMP", ImportServiceImpl.resolveDisplayName("COMP", default, default))
    }

    @Test
    @DisplayName("resolveDisplayName: an explicit, distinct value is kept verbatim")
    fun `explicit distinct value kept`() {
        assertEquals("My Component", ImportServiceImpl.resolveDisplayName("COMP", "My Component", default))
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: inherited-default + blank components collapse to keys without colliding")
    fun `inherited and blank do not collide`() {
        val modules =
            listOf(
                "COMP_A" to null, // inherits → "COMP_A"
                "COMP_B" to default, // equals default → "COMP_B"
                "COMP_C" to "Explicit C", // distinct → kept
            )
        assertTrue(ImportServiceImpl.computeDisplayNameCollisions(modules, default).isEmpty())
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: two explicit equal display names are reported with both keys")
    fun `explicit duplicates reported`() {
        val modules =
            listOf(
                "COMP_X" to "Same Name",
                "COMP_Y" to "Same Name",
                "COMP_Z" to "Unique",
            )
        val collisions = ImportServiceImpl.computeDisplayNameCollisions(modules, default)
        assertEquals(setOf("Same Name"), collisions.keys)
        assertEquals(listOf("COMP_X", "COMP_Y"), collisions["Same Name"])
    }

    @Test
    @DisplayName("computeDisplayNameCollisions: an explicit value equal to ANOTHER component's key collides")
    fun `explicit value matching another key collides`() {
        // COMP_B's key resolves to "COMP_B"; COMP_A explicitly names itself "COMP_B" → collision.
        val modules =
            listOf(
                "COMP_A" to "COMP_B",
                "COMP_B" to null,
            )
        val collisions = ImportServiceImpl.computeDisplayNameCollisions(modules, default)
        assertEquals(listOf("COMP_A", "COMP_B"), collisions["COMP_B"])
    }
}
