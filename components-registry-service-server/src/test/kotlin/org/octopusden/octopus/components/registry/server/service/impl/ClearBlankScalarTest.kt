package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * CRS-A — the single contract point for the v4 aspect-scalar clear rule. The
 * caller's `?.let` has already dropped the null/absent (no-op) case, so this
 * function only decides blank→clear vs non-blank→set-verbatim.
 */
class ClearBlankScalarTest {
    @Test
    @DisplayName("empty string clears (-> null)")
    fun `empty clears`() {
        assertNull(clearBlankScalar(""))
    }

    @Test
    @DisplayName("whitespace-only clears (-> null): blank after trim")
    fun `whitespace clears`() {
        assertNull(clearBlankScalar("   "))
        assertNull(clearBlankScalar("\t\n"))
    }

    @Test
    @DisplayName("non-blank is set verbatim — NOT trimmed")
    fun `non-blank kept verbatim`() {
        assertEquals("MAVEN", clearBlankScalar("MAVEN"))
        assertEquals("\$major.\$minor", clearBlankScalar("\$major.\$minor"))
        // No trimming of a non-blank value: surrounding spaces are preserved.
        assertEquals("  x  ", clearBlankScalar("  x  "))
    }
}
