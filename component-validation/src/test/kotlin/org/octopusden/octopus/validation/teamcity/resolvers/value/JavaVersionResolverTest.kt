package org.octopusden.octopus.validation.teamcity.resolvers.value

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaVersionResolverTest {
    private val resolver = JavaVersionResolver()

    @Nested
    @DisplayName("literal env-reference-style values (not yet resolved to a directory)")
    inner class EnvReferenceStyle {
        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource(
            "env.JDK_11, 11",
            "env.JDK_11_0, 11",
            "env.JDK_11_0_x64, 11",
            "env.JDK_11_x64, 11",
            "env.JDK_17_0, 17",
            "env.JDK_17_0_x64, 17",
            "env.JDK_18, 1.8",
            "env.JDK_18_x64, 1.8",
            "env.JDK_1_8, 1.8",
            "env.JDK_1_8_x64, 1.8",
            "env.JDK_21_0, 21",
            "env.JDK_21_0_x64, 21",
            "env.JDK_25_0, 25",
            "env.JDK_25_0_x64, 25",
            "env.JDK_ORACLE_17_x64, 17",
            "env.JDK_ORACLE_1_8_x64, 1.8",
            "env.JDK_ORACLE_1_8_x86, 1.8",
            "env.JDK_ORACLE_21_x64, 21",
            "env.JDK_ORACLE_25_x64, 25",
            "env.JDK_RH_17_x64, 17",
            "env.JDK_RH_1_8_x64, 1.8",
            "env.JDK_RH_21_x64, 21",
            "env.JDK_RH_25_x64, 25",
            "env.JDK_ZULU_17_x64, 17",
            "env.JDK_ZULU_1_8_x64, 1.8",
            "env.JDK_ZULU_21_x64, 21",
            "env.JDK_ZULU_25_x64, 25",
            "env.OPENJDK_17, 17",
            "env.OPENJDK_21, 21",
            "env.OPENJDK_25, 25",
            "env.OPENJDK_8, 8",
            "%env.BUILD_ENV%/JAVA/17, 17",
        )
        fun `resolves from the marker name alone`(
            raw: String,
            expected: String,
        ) {
            assertEquals(expected, resolver.resolve(raw)?.raw)
        }

        @Test
        @DisplayName("JDK_HOME, JRE_HOME and SOURCE_PATH markers don't name a specific version")
        fun `home and source-path markers stay unresolved`() {
            assertNull(resolver.resolve("env.JDK_HOME"))
            assertNull(resolver.resolve("env.JRE_HOME"))
            assertNull(resolver.resolve("env.JDK_SOURCE_PATH"))
        }
    }

    @Nested
    @DisplayName("already-resolved Windows directory values")
    inner class WindowsDirectoryStyle {
        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource(
            "C:\\Java\\RedHat\\11, 11",
            "C:\\Java\\RedHat\\17, 17",
            "C:\\Java\\RedHat\\1.8, 1.8",
            "C:\\Java\\RedHat\\21, 21",
            "C:\\JDK\\Oracle\\25, 25",
            "C:\\JDK\\Oracle\\17, 17",
            "C:\\JDK\\Oracle\\1.8, 1.8",
            "C:\\JDK\\Oracle\\1.8.x86, 1.8",
            "C:\\JDK\\Oracle\\21, 21",
            "C:\\JDK\\Zulu\\17, 17",
            "C:\\JDK\\Zulu\\1.8, 1.8",
            "C:\\JDK\\Zulu\\21, 21",
            "C:\\JDK\\Zulu\\25, 25",
        )
        fun `resolves from the resolved windows path`(
            raw: String,
            expected: String,
        ) {
            assertEquals(expected, resolver.resolve(raw)?.raw)
        }
    }

    @Nested
    @DisplayName("already-resolved Linux directory values")
    inner class LinuxDirectoryStyle {
        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource(
            delimiter = '|',
            value = [
                "/usr/lib/jvm/java-17-openjdk-17.0.19.0.10-2.el9.x86_64|17",
                "/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.492.b09-2.el9.x86_64|1.8",
                "/usr/lib/jvm/java-21-openjdk-21.0.11.0.10-2.el9.x86_64|21",
                "/usr/lib/jvm/java-25-openjdk|25",
                "/usr/lib/jvm/oracle/17|17",
                "/usr/lib/jvm/oracle/1.8|1.8",
                "/usr/lib/jvm/oracle/1.8.x86|1.8",
                "/usr/lib/jvm/oracle/21|21",
                "/usr/lib/jvm/oracle/25|25",
                "/usr/lib/jvm/java-17|17",
                "/usr/lib/jvm/jre-1.8.0|1.8",
                "/usr/lib/jvm/java-21|21",
                "/usr/lib/jvm/java-25|25",
                "/usr/lib/jvm/zulu/17|17",
                "/usr/lib/jvm/zulu/1.8|1.8",
                "/usr/lib/jvm/zulu/21|21",
                "/usr/lib/jvm/zulu/25|25",
            ],
        )
        fun `resolves from the resolved linux path`(
            raw: String,
            expected: String,
        ) {
            assertEquals(expected, resolver.resolve(raw)?.raw)
        }

        @Test
        @DisplayName("a build/update number after the major version doesn't get mistaken for a different major version")
        fun `leftmost match picks the major version over a trailing update number`() {
            // "21.0.11.0.10" contains a bare, digit-bounded "11" -- but "21" is the real major
            // version and appears first. Also exercises JDK_HOME/JRE_HOME resolving once the
            // env reference has actually been substituted for its real, version-bearing path.
            assertEquals("21", resolver.resolve("/usr/lib/jvm/java-21-openjdk-21.0.11.0.10-2.el9.x86_64")?.raw)
            assertEquals("25", resolver.resolve("/usr/lib/jvm/java-25-openjdk")?.raw) // env.JDK_HOME resolved
            assertEquals("25", resolver.resolve("/usr/lib/jvm/java-25-openjdk")?.raw) // env.JRE_HOME resolved
        }

        @Test
        @DisplayName("a source-path-style value with no digit token stays unresolved")
        fun `source path resolves to null`() {
            assertNull(resolver.resolve("/mnt/Q/Software/Java")) // env.JDK_SOURCE_PATH resolved
        }
    }

    @Test
    @DisplayName("resolve() returns null when no java/jdk/jvm marker is present")
    fun `resolve returns null without a known marker`() {
        assertNull(resolver.resolve("BUILD_ENV_21"))
        assertNull(resolver.resolve("SOME_BUILD_ENV/VAR/17"))
        assertNull(resolver.resolve(""))
    }

    @Test
    @DisplayName("resolve() returns null when a marker is present but no known version token is")
    fun `resolve returns null for an unknown version under a known marker`() {
        assertNull(resolver.resolve("env.JDK_9"))
    }

    @Test
    @DisplayName("resolve() does not let a known token match inside a longer digit run")
    fun `resolve respects digit boundaries`() {
        assertNull(resolver.resolve("env.JDK_217"))
        assertNull(resolver.resolve("env.JDK_125"))
    }
}
