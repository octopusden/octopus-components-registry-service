package org.octopusden.octopus.validation.teamcity.resolvers

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver
import org.octopusden.octopus.validation.teamcity.params
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParameterReferenceResolverTest {
    @Test
    @DisplayName("resolveParameter returns the raw value when it has no reference")
    fun `no reference returns raw value`() {
        val parameters = params("env.JAVA_HOME" to "/opt/jdk-21")

        assertEquals("/opt/jdk-21", ParameterReferenceResolver.resolveParameter(parameters, "env.JAVA_HOME"))
    }

    @Test
    @DisplayName("resolveParameter follows a single-hop reference")
    fun `single hop reference resolves`() {
        val parameters = params(
            "env.JAVA_HOME" to "%env.JDK_21_0_x64%",
            "env.JDK_21_0_x64" to "/opt/jdk-21",
        )

        assertEquals("/opt/jdk-21", ParameterReferenceResolver.resolveParameter(parameters, "env.JAVA_HOME"))
    }

    @Test
    @DisplayName("resolveParameter follows a multi-hop chain")
    fun `multi hop chain resolves`() {
        val parameters = params(
            "a" to "%b%",
            "b" to "%c%",
            "c" to "final-value",
        )

        assertEquals("final-value", ParameterReferenceResolver.resolveParameter(parameters, "a"))
    }

    @Test
    @DisplayName("resolveParameter returns null for a missing parameter")
    fun `missing parameter returns null`() {
        assertNull(ParameterReferenceResolver.resolveParameter(params(), "nope"))
    }

    @Test
    @DisplayName("resolveParameter leaves a reference to a missing target as literal text")
    fun `unresolved reference stays literal`() {
        val parameters = params("a" to "%missing%")

        assertEquals("%missing%", ParameterReferenceResolver.resolveParameter(parameters, "a"))
    }

    @Test
    @DisplayName("resolveParameter leaves a missing reference literal while still resolving the rest of the value")
    fun `unresolved reference stays literal within a larger value`() {
        val parameters = params("a" to "prefix-%missing%-%b%", "b" to "Y")

        assertEquals("prefix-%missing%-Y", ParameterReferenceResolver.resolveParameter(parameters, "a"))
    }

    @Test
    @DisplayName("resolveParameter returns null on a direct cycle")
    fun `direct cycle returns null`() {
        val parameters = params("a" to "%a%")

        assertNull(ParameterReferenceResolver.resolveParameter(parameters, "a"))
    }

    @Test
    @DisplayName("resolveParameter returns null on an indirect cycle")
    fun `indirect cycle returns null`() {
        val parameters = params("a" to "%b%", "b" to "%a%")

        assertNull(ParameterReferenceResolver.resolveParameter(parameters, "a"))
    }

    @Test
    @DisplayName("resolveParameter resolves multiple distinct references in the same value, repeated or not")
    fun `multiple references in one value both resolve`() {
        val parameters = params(
            "combined" to "%a%-%a%-%b%",
            "a" to "X",
            "b" to "Y",
        )

        assertEquals("X-X-Y", ParameterReferenceResolver.resolveParameter(parameters, "combined"))
    }

    @Test
    @DisplayName("resolveValue substitutes references embedded in an arbitrary string, not just a named parameter")
    fun `resolveValue substitutes embedded references`() {
        val parameters = params("env.JAVA_HOME" to "/opt/jdk-21")

        assertEquals("/opt/jdk-21/bin/java", ParameterReferenceResolver.resolveValue(parameters, "%env.JAVA_HOME%/bin/java"))
    }

    @Test
    @DisplayName("resolveValue returns the value unchanged when it has no reference")
    fun `resolveValue passthrough for plain value`() {
        val parameters = params()

        assertEquals("plain-value", ParameterReferenceResolver.resolveValue(parameters, "plain-value"))
    }
}
