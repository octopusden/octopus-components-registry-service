package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JavaVersionComparatorTest {
    @Test
    @DisplayName("legacy 1-dot-N spellings are equal to the plain major version")
    fun `valuesEqual treats legacy 1-dot-N spellings as equal to the plain major version`() {
        assertEquals(true, JavaVersionComparator.valuesEqual("1.8", "8"))
        assertEquals(true, JavaVersionComparator.valuesEqual("1.7", "7"))
        assertEquals(false, JavaVersionComparator.valuesEqual("1.8", "11"))
    }

    @Test
    @DisplayName("a longer dotted form like 17.0.9 reads by its major version")
    fun `valuesEqual reads a longer dotted form by its major version`() {
        assertEquals(true, JavaVersionComparator.valuesEqual("17.0.9", "17"))
        assertEquals(true, JavaVersionComparator.valuesEqual("1.8.0", "8"))
    }

    @Test
    @DisplayName("an unparseable value is never equal to anything")
    fun `valuesEqual returns false when either value is unparseable`() {
        assertEquals(false, JavaVersionComparator.valuesEqual("not-a-version", "8"))
        assertEquals(false, JavaVersionComparator.valuesEqual("8", "not-a-version"))
    }
}
