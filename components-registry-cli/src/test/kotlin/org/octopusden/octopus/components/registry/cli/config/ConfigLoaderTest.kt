package org.octopusden.octopus.components.registry.cli.config

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @Test
    fun `missing config file is tolerated`() {
        val absent = Files.createTempDirectory("crsctl-cfg").resolve("does-not-exist.json")
        assertSame(CrsctlConfig.EMPTY, ConfigLoader.load(absent))
    }

    @Test
    fun `blank config file yields empty`() {
        val file = Files.createTempFile("crsctl-cfg", ".json")
        file.writeText("   ")
        assertSame(CrsctlConfig.EMPTY, ConfigLoader.load(file))
    }

    @Test
    fun `valid config parses profiles and default`() {
        val file = Files.createTempFile("crsctl-cfg", ".json")
        file.writeText(
            """
            {
              "defaultProfile": "qa",
              "profiles": {
                "qa": { "crsUrl": "https://qa.crs", "clientId": "crsctl" }
              }
            }
            """.trimIndent(),
        )
        val config = ConfigLoader.load(file)
        assertEquals("qa", config.defaultProfile)
        assertEquals("https://qa.crs", config.profiles["qa"]?.crsUrl)
        assertEquals("crsctl", config.profiles["qa"]?.clientId)
    }

    @Test
    fun `malformed config file throws`() {
        val file = Files.createTempFile("crsctl-cfg", ".json")
        file.writeText("{ not json")
        assertFailsWith<ConfigLoadException> { ConfigLoader.load(file) }
    }

    @Test
    fun `config dir is non-empty and ends with crsctl`() {
        assertTrue(ConfigLoader.configDir().endsWith("crsctl"))
    }
}
