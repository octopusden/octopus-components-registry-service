package org.octopusden.octopus.components.registry.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrsctlTest {
    @Test
    fun `help runs without throwing and prints the command name`() {
        val result = Crsctl().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("crsctl"), "help output should mention the command name")
    }

    @Test
    fun `version flag reports the version`() {
        val result = Crsctl().test("--version")
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains(Crsctl.VERSION), "version output should contain ${Crsctl.VERSION}")
    }
}
