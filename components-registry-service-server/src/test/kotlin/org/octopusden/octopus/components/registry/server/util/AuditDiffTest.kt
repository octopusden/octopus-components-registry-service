package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AuditDiffTest {
    @Test
    fun `returns null when either side is null`() {
        assertNull(AuditDiff.compute(null, mapOf("a" to 1)))
        assertNull(AuditDiff.compute(mapOf("a" to 1), null))
        assertNull(AuditDiff.compute(null, null))
    }

    @Test
    fun `returns null when maps are equal`() {
        assertNull(AuditDiff.compute(mapOf("a" to 1, "b" to "x"), mapOf("a" to 1, "b" to "x")))
    }

    @Test
    fun `diff format is key to old-new pair`() {
        val diff = AuditDiff.compute(mapOf("a" to 1, "b" to "x"), mapOf("a" to 2, "b" to "x"))
        assertEquals(mapOf("a" to mapOf("old" to 1, "new" to 2)), diff)
    }

    @Test
    fun `missing keys on either side are diffed as null`() {
        val diff = AuditDiff.compute(mapOf("a" to 1), mapOf("b" to 2))
        assertEquals(
            mapOf(
                "a" to mapOf("old" to 1, "new" to null),
                "b" to mapOf("old" to null, "new" to 2),
            ),
            diff,
        )
    }
}
