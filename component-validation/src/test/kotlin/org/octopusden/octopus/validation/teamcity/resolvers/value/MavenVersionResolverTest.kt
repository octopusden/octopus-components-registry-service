package org.octopusden.octopus.validation.teamcity.resolvers.value

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.MavenVersionResolver
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MavenVersionResolverTest {
    private val resolver = MavenVersionResolver()

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "env.MAVEN_3.6.3, 3.6.3",
        "MAVEN_HOME/3.6.0, 3.6.0",
        "%env.MAVEN%/3.3.9, 3.3.9",
        "env.MAVEN/LATEST, LATEST",
        "env.MAVEN_3, 3",
        "apache-maven-3.6.3, 3.6.3",
    )
    @DisplayName("resolve() finds the known version token when a maven marker is present")
    fun `resolve extracts version`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, resolver.resolve(raw)?.raw)
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "%env.BUILD_ENV%/MAVEN/3.6, 3.6",
        "%env.BUILD_ENV%/MAVEN/3.9, 3.9",
        "%env.BUILD_ENV%/MAVEN/3.10, 3.10",
        "%env.BUILD_ENV%/MAVEN/3.6.3, 3.6.3",
        "%env.BUILD_ENV%/MAVEN/LATEST, LATEST",
        "M2_HOME=/opt/BUILD_ENV/MAVEN/3.9, 3.9",
        "/opt/BUILD_ENV/MAVEN/3.10/bin/mvn, 3.10",
        "%%BUILD_ENV%%\\MAVEN\\3.6\\bin\\mvn, 3.6",
    )
    @DisplayName("SYS-106: resolve() takes the whole version from a MAVEN/<version> path segment")
    fun `SYS-106 resolve takes the version from the MAVEN path segment`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, resolver.resolve(raw)?.raw)
    }

    @Test
    @DisplayName("SYS-106: versions of different Maven lines resolve to different versions")
    fun `SYS-106 different Maven lines are told apart`() {
        val versions =
            listOf("MAVEN/3.6", "MAVEN/3.6.3", "MAVEN/3.9", "MAVEN/3.10")
                .map { resolver.resolve("%env.BUILD_ENV%/$it")?.raw }
        assertEquals(listOf("3.6", "3.6.3", "3.9", "3.10"), versions)
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "/opt/apache-MAVEN/3.10/bin/mvn, 3",
        "/opt/NOT_MAVEN/3.10, 3",
        "/opt/MyMAVEN/3.6.3, 3.6.3",
    )
    @DisplayName("SYS-106: MAVEN that is only part of a path element is not a version segment")
    fun `SYS-106 MAVEN inside a longer path element falls back to known tokens`(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected, resolver.resolve(raw)?.raw)
    }

    @Test
    @DisplayName("resolve() returns null without a maven marker")
    fun `resolve returns null without the marker`() {
        assertNull(resolver.resolve("BUILD_ENV_3.6.3"))
        assertNull(resolver.resolve("/opt/apache-3.8.6"))
        assertNull(resolver.resolve(""))
    }

    @Test
    @DisplayName("resolve() returns null when maven marker is present but no known version token is")
    fun `resolve returns null for an unknown version under the marker`() {
        assertNull(resolver.resolve("env.MAVEN_4_0_0"))
    }

    @Test
    @DisplayName("resolve() prefers the longer, more specific token over the bare '3'")
    fun `resolve does not let bare 3 shadow a more specific version`() {
        assertEquals("3.3.9", resolver.resolve("env.MAVEN_3.3.9")?.raw)
    }

    @Test
    @DisplayName("resolve() does not let bare '3' match inside a longer digit run")
    fun `resolve respects digit boundaries`() {
        assertNull(resolver.resolve("env.MAVEN_33"))
    }
}
