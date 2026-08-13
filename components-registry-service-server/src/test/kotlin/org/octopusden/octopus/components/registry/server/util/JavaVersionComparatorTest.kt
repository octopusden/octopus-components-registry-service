package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    @DisplayName("compare orders by major version, treating legacy 1-dot-N spellings as equal")
    fun `compare orders by major version`() {
        assertEquals(true, JavaVersionComparator.compare("8", "11") < 0)
        assertEquals(true, JavaVersionComparator.compare("11", "8") > 0)
        assertEquals(true, JavaVersionComparator.compare("1.8", "8") == 0)
    }

    @Test
    @DisplayName("compare throws for a value with no extractable major version")
    fun `compare throws when either value is unparseable`() {
        assertThrows(IllegalArgumentException::class.java) { JavaVersionComparator.compare("not-a-version", "8") }
        assertThrows(IllegalArgumentException::class.java) { JavaVersionComparator.compare("8", "not-a-version") }
    }

    @Test
    @DisplayName("sorting a list of raw Java version strings by compare orders them by major version")
    fun `sorting by compare orders a list by major version`() {
        val sorted = listOf("17", "1.8", "11", "8").sortedWith(JavaVersionComparator::compare)
        assertEquals(listOf("1.8", "8", "11", "17"), sorted)
    }
}
