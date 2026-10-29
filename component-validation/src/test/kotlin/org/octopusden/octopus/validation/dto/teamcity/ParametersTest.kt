package org.octopusden.octopus.validation.dto.teamcity

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParametersTest {
    @Test
    @DisplayName("get returns the value for a present parameter")
    fun `get returns present value`() {
        val parameters = Parameters(mapOf("a" to "1"))

        assertEquals("1", parameters["a"])
    }

    @Test
    @DisplayName("get returns null for a missing parameter")
    fun `get returns null for missing`() {
        val parameters = Parameters(emptyMap())

        assertNull(parameters["missing"])
    }

    @Test
    @DisplayName("require returns the value for a present parameter")
    fun `require returns present value`() {
        val parameters = Parameters(mapOf("a" to "1"))

        assertEquals("1", parameters.require("a"))
    }

    @Test
    @DisplayName("require throws for a missing parameter")
    fun `require throws for missing`() {
        val parameters = Parameters(emptyMap())

        assertFailsWith<NoSuchElementException> { parameters.require("missing") }
    }
}
