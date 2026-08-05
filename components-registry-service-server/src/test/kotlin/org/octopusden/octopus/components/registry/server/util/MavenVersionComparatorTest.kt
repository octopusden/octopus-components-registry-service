package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MavenVersionComparatorTest {
    @Test
    @DisplayName("values compare via version-aware comparison, not raw string equality")
    fun `valuesEqual uses version-aware comparison`() {
        assertEquals(true, MavenVersionComparator.valuesEqual("3.3.6", "3.3.6"))
        assertEquals(false, MavenVersionComparator.valuesEqual("3.3.6", "3.3.9"))
    }

    @Test
    @DisplayName("LATEST is never equal to a numbered version, and always wins a comparison")
    fun `valuesEqual and compare treat LATEST as its own distinct, always-winning value`() {
        assertEquals(false, MavenVersionComparator.valuesEqual("LATEST", "4.0"))
        assertEquals(true, MavenVersionComparator.valuesEqual("LATEST", "LATEST"))
        assertEquals(true, MavenVersionComparator.compare("LATEST", "4.0") > 0)
        assertEquals(true, MavenVersionComparator.compare("4.0", "LATEST") < 0)
        assertEquals(true, MavenVersionComparator.compare("LATEST", "LATEST") == 0)
    }
}
